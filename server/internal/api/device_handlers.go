package api

import (
	"net/http"
	"strconv"
	"strings"

	"videocall/internal/auth"
)

type registerDeviceKeyRequest struct {
	DeviceID     string `json:"device_id"`
	PublicKeyJWK string `json:"public_key_jwk"`
}

// handleRegisterDeviceKey publishes the caller's device's E2E public key.
// Public keys aren't secret by definition — this just makes one discoverable
// to anyone who wants to encrypt a message to this user.
func (a *API) handleRegisterDeviceKey(w http.ResponseWriter, r *http.Request) {
	me := auth.CurrentUser(r)
	var req registerDeviceKeyRequest
	if err := readJSON(r, &req); err != nil {
		writeErr(w, http.StatusBadRequest, "invalid request body")
		return
	}
	req.DeviceID = strings.TrimSpace(req.DeviceID)
	if req.DeviceID == "" || len(req.DeviceID) > 128 || req.PublicKeyJWK == "" || len(req.PublicKeyJWK) > 4096 {
		writeErr(w, http.StatusBadRequest, "invalid device_id or public_key_jwk")
		return
	}
	if err := a.db.UpsertDeviceKey(me.ID, req.DeviceID, req.PublicKeyJWK); err != nil {
		writeErr(w, http.StatusInternalServerError, "could not register device key")
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

// handleDeviceKeys returns the registered device keys for a set of users —
// what a client needs before it can encrypt a message to them. Any
// authenticated user may look up any other user's keys: they're public by
// definition, and this app has no concept of a private directory.
// maxDeviceKeyLookupIDs caps how many user IDs a single request can ask
// for at once — generous for any real conversation/group size, but bounded
// so an arbitrarily long attacker-supplied list can't build an unbounded
// SQL IN (...) clause.
const maxDeviceKeyLookupIDs = 500

func (a *API) handleDeviceKeys(w http.ResponseWriter, r *http.Request) {
	raw := strings.Split(r.URL.Query().Get("user_ids"), ",")
	if len(raw) > maxDeviceKeyLookupIDs {
		writeErr(w, http.StatusBadRequest, "too many user_ids in one request")
		return
	}
	ids := make([]int64, 0, len(raw))
	for _, s := range raw {
		s = strings.TrimSpace(s)
		if s == "" {
			continue
		}
		id, err := strconv.ParseInt(s, 10, 64)
		if err != nil {
			writeErr(w, http.StatusBadRequest, "invalid user_ids")
			return
		}
		ids = append(ids, id)
	}
	keys, err := a.db.DeviceKeysForUsers(ids)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "could not load device keys")
		return
	}
	writeJSON(w, http.StatusOK, keys)
}
