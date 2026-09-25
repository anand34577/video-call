package signaling

import (
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"

	"visioncall/internal/auth"
	"visioncall/internal/config"
	"visioncall/internal/db"
	"visioncall/internal/settings"
	"visioncall/internal/sfu"
)

// Envelope is the single WS message shape in both directions.
type Envelope struct {
	Type string          `json:"type"`
	Data json.RawMessage `json:"data,omitempty"`
}

type Hub struct {
	cfg      *config.Config
	db       *db.DB
	engine   *sfu.Engine
	log      *slog.Logger
	settings *settings.Store

	mu      sync.RWMutex
	clients map[int64]*Client
	// active conference roomID -> call DB row id
	roomCalls       map[string]int64
	roomLeaveTimers map[int64]*time.Timer
	roomLeaveTokens map[int64]uint64
	callStateMu     sync.Mutex

	p2p          *p2pManager
	privateRooms *privateRoomGate
	// passcodes throttles wrong private-room passcodes per (user, room) —
	// without it a short numeric passcode falls to brute force over one
	// socket, each guess costing the server an argon2 hash.
	passcodes *auth.LoginLimiter
	upgrader  websocket.Upgrader
	closed    atomic.Bool

	// lastStatus remembers each user's last explicitly-chosen presence status
	// ("away"/"dnd") across reconnects — a Client's status lives on that one
	// connection and is lost when it's replaced, so without this a reconnect
	// (network blip, laptop sleep/wake, tab backgrounded) would silently reset
	// a user-set DND back to "online" on every new connection.
	lastStatus sync.Map // int64 user id -> string status
}

func (h *Hub) rememberStatus(userID int64, status string) {
	h.lastStatus.Store(userID, status)
}

func (h *Hub) recallStatus(userID int64) string {
	if v, ok := h.lastStatus.Load(userID); ok {
		if s, ok := v.(string); ok && s != "" {
			return s
		}
	}
	return "online"
}

func NewHub(cfg *config.Config, dbh *db.DB, log *slog.Logger, settingsStore *settings.Store) *Hub {
	h := &Hub{
		cfg:             cfg,
		db:              dbh,
		log:             log,
		settings:        settingsStore,
		clients:         map[int64]*Client{},
		roomCalls:       map[string]int64{},
		roomLeaveTimers: map[int64]*time.Timer{},
		roomLeaveTokens: map[int64]uint64{},
		privateRooms:    newPrivateRoomGate(),
		passcodes:       auth.NewLoginLimiter(),
		upgrader: websocket.Upgrader{
			ReadBufferSize:  4096,
			WriteBufferSize: 4096,
			CheckOrigin: func(r *http.Request) bool {
				// Same-origin only; absent Origin (native clients / curl) is allowed.
				origin := r.Header.Get("Origin")
				return origin == "" || origin == "http://"+r.Host || origin == "https://"+r.Host
			},
		},
	}
	h.p2p = newP2PManager(h)
	// MaxParticipants/ExternalIP are "restart to take effect" settings — the
	// SFU engine doesn't re-read them mid-run, but a restart after an admin
	// edit does, since settingsStore.Get() already merges env/.env/DB/default.
	sv := settingsStore.Get()
	h.engine = sfu.NewEngine(sfu.Config{
		MaxParticipants: sv.MaxCallParticipants,
		ExternalIP:      sv.ExternalIP,
		FallbackIP:      config.DetectPrimaryLANIP(),
		UDPPort:         cfg.WebRTCUDPPort,
	}, log)
	h.engine.OnEvent = h.onRoomEvent
	return h
}

// HandleWS upgrades an authenticated HTTP request into a client WebSocket session.
func (h *Hub) HandleWS(w http.ResponseWriter, r *http.Request) {
	if h.closed.Load() {
		http.Error(w, "server shutting down", http.StatusServiceUnavailable)
		return
	}

	cookie, err := r.Cookie(auth.CookieName)
	if err != nil {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	user, err := auth.ValidateSession(h.db, h.cfg.JWTSecret, cookie.Value)
	if err != nil {
		h.log.Warn("ws: invalid session", "ip", realIP(r, h.settings.Get().TrustProxy), "err", err)
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	claims, err := auth.ParseToken(h.cfg.JWTSecret, cookie.Value)
	if err != nil {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	if user.Disabled {
		http.Error(w, "account disabled", http.StatusForbidden)
		return
	}

	conn, err := h.upgrader.Upgrade(w, r, nil)
	if err != nil {
		h.log.Warn("ws: upgrade failed", "user_id", user.ID, "ip", realIP(r, h.settings.Get().TrustProxy), "err", err)
		return
	}

	ip := realIP(r, h.settings.Get().TrustProxy)
	clog := h.log.With("user_id", user.ID, "username", user.Username, "ip", ip)
	c := &Client{
		hub:       h,
		user:      user,
		conn:      conn,
		send:      make(chan []byte, 256),
		log:       clog,
		deviceID:  r.URL.Query().Get("device"),
		host:      r.Host,
		sessionID: claims.SessionID,
	}
	h.register(c)
	clog.Info("ws: connected")
	go c.writePump()
	go c.readPump()
}

func realIP(r *http.Request, trustProxy bool) string { return auth.RealIP(r, trustProxy) }

func (h *Hub) register(c *Client) {
	h.callStateMu.Lock()
	h.mu.Lock()
	old := h.clients[c.user.ID]
	if old != nil {
		// A fresh session replaces the old one. Keep an SFU participant
		// alive briefly so a reconnect can resume, but end P2P calls because
		// their signaling state cannot be resumed safely.
		h.scheduleRoomLeaveLocked(c.user.ID)
	}
	h.clients[c.user.ID] = c
	h.mu.Unlock()
	if old != nil {
		sameDevice := old.deviceID != "" && old.deviceID == c.deviceID
		if sameDevice {
			// Same browser, but not necessarily the same tab: a plain tab
			// refresh looks identical here to "a second tab of this account
			// is now open", and a silent close used to make the replaced tab
			// immediately auto-reconnect - which this same branch would then
			// silently close again, forever (this was the actual cause of
			// "Realtime connection lost" firing repeatedly with more than one
			// tab open). ws:replaced tells that tab's client to stop
			// reconnecting on its own instead of fighting over the slot.
			h.log.Debug("ws: replacing existing session (same device)", "user_id", c.user.ID)
			old.Finish("ws:replaced", nil)
			// Most likely the same tab reconnecting: a 1:1 call survives if the
			// client sends call:resume within the grace period.
			h.p2p.scheduleDisconnect(c.user.ID)
		} else {
			// A different device/browser signed in to this account. Only one
			// session is active at a time, so tell the old one clearly instead
			// of silently dropping it (which would otherwise just make it
			// auto-reconnect and re-kick the new session in a loop).
			h.log.Info("ws: session replaced by another device", "user_id", c.user.ID)
			// Revoke the old session too, not just its socket - otherwise its
			// reload (see App.tsx's forceSignOut) silently re-authenticates
			// with the still-valid token and re-kicks this new session,
			// fighting forever.
			if old.sessionID != "" {
				_ = h.db.DeleteSession(old.sessionID)
			}
			old.Kick("signed_in_elsewhere")
			h.p2p.onDisconnect(c.user.ID) // the call lived on the other device
		}
	}
	h.callStateMu.Unlock()
	h.onConnect(c)
}

func (h *Hub) unregister(c *Client) {
	h.callStateMu.Lock()
	defer h.callStateMu.Unlock()
	h.mu.Lock()
	current := h.clients[c.user.ID]
	if current == c {
		delete(h.clients, c.user.ID)
		if h.closed.Load() {
			h.mu.Unlock()
			return
		}
		h.scheduleRoomLeaveLocked(c.user.ID)
	}
	h.mu.Unlock()
	if current != c {
		// We were replaced by a newer session; nothing to tear down.
		return
	}
	c.log.Info("ws: disconnected")
	h.broadcast("presence:update", map[string]any{"user_id": c.user.ID, "status": "offline"}, nil)
	h.p2p.scheduleDisconnect(c.user.ID)
	h.privateRooms.dropUserEverywhere(c.user.ID)
}

const sfuResumeGrace = 15 * time.Second

// scheduleRoomLeaveLocked delays SFU teardown after a signaling disconnect so
// the browser can reconnect and swap the participant's signal callback.
// Caller holds callStateMu and h.mu.
func (h *Hub) scheduleRoomLeaveLocked(userID int64) {
	if h.roomLeaveTimers == nil {
		h.roomLeaveTimers = map[int64]*time.Timer{}
	}
	if h.roomLeaveTokens == nil {
		h.roomLeaveTokens = map[int64]uint64{}
	}
	if timer := h.roomLeaveTimers[userID]; timer != nil {
		timer.Stop()
	}
	token := h.roomLeaveTokens[userID] + 1
	h.roomLeaveTokens[userID] = token
	h.roomLeaveTimers[userID] = time.AfterFunc(sfuResumeGrace, func() {
		h.expireRoomLeave(userID, token)
	})
}

func (h *Hub) cancelRoomLeave(userID int64) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.roomLeaveTokens == nil {
		h.roomLeaveTokens = map[int64]uint64{}
	}
	if timer := h.roomLeaveTimers[userID]; timer != nil {
		timer.Stop()
		delete(h.roomLeaveTimers, userID)
	}
	h.roomLeaveTokens[userID]++
}

func (h *Hub) expireRoomLeave(userID int64, token uint64) {
	h.callStateMu.Lock()
	defer h.callStateMu.Unlock()
	h.mu.Lock()
	if h.roomLeaveTokens[userID] != token {
		h.mu.Unlock()
		return
	}
	delete(h.roomLeaveTimers, userID)
	delete(h.roomLeaveTokens, userID)
	h.mu.Unlock()
	if h.closed.Load() {
		return
	}
	h.engine.Leave(userID)
}

func (h *Hub) client(userID int64) *Client {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return h.clients[userID]
}

// sendToUser delivers a message if the user is connected; returns false if offline.
func (h *Hub) sendToUser(userID int64, typ string, data any) bool {
	c := h.client(userID)
	if c == nil {
		return false
	}
	return c.Send(typ, data)
}

func (h *Hub) broadcast(typ string, data any, except *int64) {
	payload, err := marshalEnvelope(typ, data)
	if err != nil {
		h.log.Error("ws: marshal broadcast", "type", typ, "err", err)
		return
	}
	h.mu.RLock()
	defer h.mu.RUnlock()
	for id, c := range h.clients {
		if except != nil && id == *except {
			continue
		}
		c.SendRaw(payload)
	}
}

func marshalEnvelope(typ string, data any) ([]byte, error) {
	raw, err := json.Marshal(data)
	if err != nil {
		return nil, err
	}
	return json.Marshal(Envelope{Type: typ, Data: raw})
}

// onConnect pushes everything the client missed while offline.
func (h *Hub) onConnect(c *Client) {
	c.Send("hello", map[string]any{"user": briefOf(c.user)})
	status := h.recallStatus(c.user.ID)
	c.setStatus(status)
	h.broadcast("presence:update", map[string]any{"user_id": c.user.ID, "status": status}, nil)
	c.Send("presence:sync", map[string]any{"users": h.presenceList()})

	// Offline delivery: direct messages that were sent but never marked delivered.
	msgs, err := h.db.UndeliveredDirect(c.user.ID, 200)
	if err != nil {
		h.log.Error("ws: fetch undelivered", "user_id", c.user.ID, "err", err)
	} else if len(msgs) > 0 {
		now := time.Now().UTC().Format(time.RFC3339)
		toMark := make([]int64, 0, len(msgs))
		h.enrichBatch(msgs)
		for _, m := range msgs {
			if c.Send("message:new", map[string]any{"message": m}) {
				m.DeliveredAt = &now
				toMark = append(toMark, m.ID)
			}
		}
		if len(toMark) > 0 {
			if err := h.db.MarkDelivered(toMark, now); err != nil {
				h.log.Error("ws: mark delivered", "user_id", c.user.ID, "err", err)
			}
		}
		c.log.Info("ws: delivered offline messages", "count", len(msgs))
	}

	counts, err := h.db.UnreadDirectCounts(c.user.ID)
	if err != nil {
		h.log.Error("ws: fetch unread direct counts", "user_id", c.user.ID, "err", err)
		counts = map[int64]int{}
	}
	groupCounts, err := h.db.GroupUnreadCounts(c.user.ID)
	if err != nil {
		h.log.Error("ws: fetch unread group counts", "user_id", c.user.ID, "err", err)
		groupCounts = map[int64]int{}
	}
	if len(counts) > 0 || len(groupCounts) > 0 {
		c.Send("message:unread", map[string]any{"counts": counts, "group_counts": groupCounts})
	}
}

func (h *Hub) presenceList() []map[string]any {
	h.mu.RLock()
	defer h.mu.RUnlock()
	out := make([]map[string]any, 0, len(h.clients))
	for id, c := range h.clients {
		out = append(out, map[string]any{"user_id": id, "status": c.status()})
	}
	return out
}

// GetPresence implements api.PresenceProvider.
func (h *Hub) GetPresence() map[int64]string {
	h.mu.RLock()
	defer h.mu.RUnlock()
	out := make(map[int64]string, len(h.clients))
	for id, c := range h.clients {
		out[id] = c.status()
	}
	return out
}

func (h *Hub) OnlineCount() int {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return len(h.clients)
}

func (h *Hub) ActiveCalls() int {
	return h.p2p.count() + h.engine.RoomCount()
}

// KickUser force-disconnects a user (e.g. admin disabled / deleted the account).
func (h *Hub) KickUser(userID int64) {
	h.callStateMu.Lock()
	defer h.callStateMu.Unlock()
	if c := h.client(userID); c != nil {
		c.log.Info("ws: user kicked by admin")
		c.Kick("account updated")
	}
	h.cancelRoomLeave(userID)
	h.engine.Leave(userID)
	h.p2p.onDisconnect(userID)
}

// EvictFromGroupRoom removes targetID from the conference room bound to
// groupID, if they're currently in it — used when an admin/owner removes
// them from the group itself while a call for that group is in progress, so
// "removed from the group" actually means "off the call", not "still
// watching/listening until they personally hang up". Returns whether an
// eviction happened (false if they weren't on that call).
func (h *Hub) EvictFromGroupRoom(groupID, targetID int64) bool {
	h.callStateMu.Lock()
	defer h.callStateMu.Unlock()
	roomID := fmt.Sprintf("group:%d", groupID)
	h.sendToUser(targetID, "room:kicked", map[string]any{
		"room_id": roomID,
		"reason":  "removed-from-group",
	})
	h.cancelRoomLeave(targetID)
	return h.engine.LeaveRoom(roomID, targetID)
}

// CloseRoom forcibly ends a conference room, used when its owning group is
// deleted. It is serialized with joins so no participant can re-enter while
// the room is being torn down.
func (h *Hub) CloseRoom(roomID string) {
	h.callStateMu.Lock()
	defer h.callStateMu.Unlock()
	for _, userID := range h.engine.RoomUserIDs(roomID) {
		h.sendToUser(userID, "room:closed", map[string]any{
			"room_id": roomID,
			"reason":  "group-deleted",
		})
	}
	h.engine.CloseRoom(roomID)
}

// Close tears down all clients and rooms (graceful server shutdown).
func (h *Hub) Close() {
	h.closed.Store(true)
	h.callStateMu.Lock()
	h.mu.Lock()
	clients := make([]*Client, 0, len(h.clients))
	for _, c := range h.clients {
		clients = append(clients, c)
	}
	h.clients = map[int64]*Client{}
	for userID, timer := range h.roomLeaveTimers {
		timer.Stop()
		delete(h.roomLeaveTimers, userID)
	}
	h.roomLeaveTokens = map[int64]uint64{}
	h.mu.Unlock()
	for _, c := range clients {
		c.close()
	}
	h.p2p.closeAll()
	h.engine.Close()
	h.passcodes.Stop()
	h.callStateMu.Unlock()
	h.log.Info("ws: hub closed")
}

func briefOf(u *db.User) *db.UserBrief {
	return &db.UserBrief{ID: u.ID, DisplayName: u.DisplayName, Username: u.Username, AvatarFileID: u.AvatarFileID}
}
