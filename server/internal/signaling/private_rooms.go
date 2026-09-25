package signaling

import (
	"encoding/json"
	"strconv"
	"strings"
	"sync"

	"visioncall/internal/auth"
)

// Standalone private rooms (not tied to a chat group) live at
// roomID = "priv:<code>". Joining one is gated by a passcode, an invite
// list, and/or host approval — all enforced here before the SFU engine
// ever sees the join. Approval is a two-step handshake over the same
// socket: the requester's first "room:join" parks as pending and gets
// "room:join-pending"; the host answers with room:admit/room:deny; on
// admit the requester is marked pre-approved and must resend "room:join"
// (its own socket is what the SFU engine actually publishes/subscribes
// on, so the server can't complete the join on the requester's behalf).

func privRoomID(roomID string) (string, bool) {
	id := strings.TrimPrefix(roomID, "priv:")
	if id == roomID || id == "" {
		return "", false
	}
	return id, true
}

// Entries clear on admit/deny, on explicit room:leave, and on socket
// disconnect (dropUserEverywhere) — so a requester who goes offline before
// the host answers, or before consuming an approval, can't leave a stray
// approval sitting around to be replayed later.
type privateRoomGate struct {
	mu       sync.Mutex
	pending  map[string]map[int64]bool // roomID -> requester userIDs awaiting host approval
	approved map[string]map[int64]bool // roomID -> userIDs approved to bypass the gate once
}

func newPrivateRoomGate() *privateRoomGate {
	return &privateRoomGate{
		pending:  map[string]map[int64]bool{},
		approved: map[string]map[int64]bool{},
	}
}

func (g *privateRoomGate) markPending(roomID string, userID int64) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.pending[roomID] == nil {
		g.pending[roomID] = map[int64]bool{}
	}
	g.pending[roomID][userID] = true
}

func (g *privateRoomGate) clearPending(roomID string, userID int64) (wasPending bool) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.pending[roomID] == nil {
		return false
	}
	wasPending = g.pending[roomID][userID]
	delete(g.pending[roomID], userID)
	return wasPending
}

func (g *privateRoomGate) markApproved(roomID string, userID int64) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.approved[roomID] == nil {
		g.approved[roomID] = map[int64]bool{}
	}
	g.approved[roomID][userID] = true
}

// consumeApproved reports and clears a one-time approval, so a stale
// approval can't be replayed to skip the gate on a later, unrelated join.
func (g *privateRoomGate) consumeApproved(roomID string, userID int64) bool {
	g.mu.Lock()
	defer g.mu.Unlock()
	if !g.approved[roomID][userID] {
		return false
	}
	delete(g.approved[roomID], userID)
	return true
}

// dropUserEverywhere clears userID's pending/approved state across every
// private room. Called on socket disconnect: a pending join request or a
// host's approval is only meant for that one join attempt, and the map is
// small (rooms x concurrent join attempts) so a full scan is cheap. Without
// this, an approval granted while the requester was offline (so they never
// got to consume it) sits valid indefinitely and lets a later, unrelated
// join skip the passcode/invite-list checks in authorizePrivateRoomJoin.
func (g *privateRoomGate) dropUserEverywhere(userID int64) {
	g.mu.Lock()
	defer g.mu.Unlock()
	for _, m := range g.pending {
		delete(m, userID)
	}
	for _, m := range g.approved {
		delete(m, userID)
	}
}

// authorizePrivateRoomJoin runs every gate for a "priv:<id>" room join and
// reports whether the caller should proceed to engine.Join now. When it
// returns false it has already sent the requester (and, for the approval
// case, the host) whatever response applies — the caller just returns.
func (c *Client) authorizePrivateRoomJoin(id string, passcode *string) bool {
	room, err := c.hub.db.GetPrivateRoom(id)
	if err != nil {
		c.roomJoinError("room not found")
		return false
	}
	if room.OwnerID == c.user.ID {
		return true
	}
	if c.hub.privateRooms.consumeApproved(id, c.user.ID) {
		return true
	}
	allowed, err := c.hub.db.IsPrivateRoomAllowed(id, c.user.ID)
	if err != nil {
		c.roomJoinError("could not verify invite")
		return false
	}
	if !allowed {
		c.roomJoinError("you are not invited to this room")
		return false
	}
	if room.PasscodeHash != nil {
		if passcode == nil || *passcode == "" {
			c.roomJoinError("this room requires a passcode")
			return false
		}
		key := id + "|" + strconv.FormatInt(c.user.ID, 10)
		if !c.hub.passcodes.Reserve(key) {
			c.roomJoinError("too many wrong passcodes; try again in a few minutes")
			return false
		}
		ok, err := auth.VerifyPassword(*room.PasscodeHash, *passcode)
		if err != nil || !ok {
			c.roomJoinError("incorrect passcode")
			return false
		}
		c.hub.passcodes.Success(key)
	}
	if room.RequireApproval {
		c.hub.privateRooms.markPending(id, c.user.ID)
		c.Send("room:join-pending", map[string]any{"room_id": "priv:" + id})
		c.hub.sendToUser(room.OwnerID, "room:join-request", map[string]any{
			"room_id": "priv:" + id,
			"from":    briefOf(c.user),
		})
		return false
	}
	return true
}

type roomAdmitPayload struct {
	RoomID string `json:"room_id"`
	UserID int64  `json:"user_id"`
}

func (c *Client) handleRoomAdmit(env *Envelope) { c.respondToJoinRequest(env, true) }
func (c *Client) handleRoomDeny(env *Envelope)  { c.respondToJoinRequest(env, false) }

func (c *Client) respondToJoinRequest(env *Envelope, admit bool) {
	var p roomAdmitPayload
	if json.Unmarshal(env.Data, &p) != nil || p.UserID == 0 {
		return
	}
	id, ok := privRoomID(p.RoomID)
	if !ok {
		return
	}
	room, err := c.hub.db.GetPrivateRoom(id)
	if err != nil || room.OwnerID != c.user.ID {
		return // only the host decides
	}
	if !c.hub.privateRooms.clearPending(id, p.UserID) {
		return // no such pending request (already resolved, or never existed)
	}
	if admit {
		c.hub.privateRooms.markApproved(id, p.UserID)
		c.hub.sendToUser(p.UserID, "room:join-approved", map[string]any{"room_id": p.RoomID})
	} else {
		c.hub.sendToUser(p.UserID, "room:join-denied", map[string]any{"room_id": p.RoomID})
	}
}
