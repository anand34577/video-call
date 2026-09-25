package api

import (
	"net/http"

	"github.com/go-chi/chi/v5"

	"videocall/internal/auth"
)

// handleListSettings powers the admin Settings screen: every setting the
// app has, dynamic or static, with its current value (secrets masked), and
// where that value came from — so an admin can see at a glance which
// settings are theirs to change here and which are pinned by the
// environment (and therefore need a .env/Docker edit + restart instead).
func (a *API) handleListSettings(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, a.settings.List())
}

type updateSettingRequest struct {
	Value string `json:"value"`
}

func (a *API) handleUpdateSetting(w http.ResponseWriter, r *http.Request) {
	key := chi.URLParam(r, "key")
	var req updateSettingRequest
	if err := readJSON(r, &req); err != nil {
		writeErr(w, http.StatusBadRequest, "invalid request body")
		return
	}
	me := auth.CurrentUser(r)
	if err := a.settings.Set(key, req.Value, me.ID); err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	a.audit(r, "setting_update", "setting", nil, key)
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

// handleResetSetting clears a Settings-screen override, falling back to
// whatever the environment/default would otherwise give it.
func (a *API) handleResetSetting(w http.ResponseWriter, r *http.Request) {
	key := chi.URLParam(r, "key")
	if err := a.settings.Reset(key); err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	a.audit(r, "setting_reset", "setting", nil, key)
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}
