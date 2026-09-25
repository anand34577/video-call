package api

import (
	"net/http"
	"strconv"

	"videocall/internal/auth"
	"videocall/internal/db"
)

// audit records one audit-log entry for the current request's actor. Errors
// are logged, not surfaced — a broken audit write must never block the
// action it's describing.
func (a *API) audit(r *http.Request, action, targetType string, targetID *int64, detail string) {
	me := auth.CurrentUser(r)
	var actorID *int64
	name := "unknown"
	if me != nil {
		id := me.ID
		actorID = &id
		name = me.Username
	}
	if err := a.db.WriteAudit(actorID, name, action, targetType, targetID, detail, RealIP(r, a.settings.Get().TrustProxy)); err != nil && a.log != nil {
		a.log.Warn("write audit entry", "action", action, "err", err)
	}
}

// auditAnon is for actions with no authenticated user yet (login attempts).
func (a *API) auditAnon(r *http.Request, actorName, action, detail string) {
	if err := a.db.WriteAudit(nil, actorName, action, "", nil, detail, RealIP(r, a.settings.Get().TrustProxy)); err != nil && a.log != nil {
		a.log.Warn("write audit entry", "action", action, "err", err)
	}
}

func (a *API) handleListAudit(w http.ResponseWriter, r *http.Request) {
	f := db.AuditFilter{Action: r.URL.Query().Get("action")}
	if v := r.URL.Query().Get("actor_id"); v != "" {
		id, err := strconv.ParseInt(v, 10, 64)
		if err != nil {
			writeErr(w, http.StatusBadRequest, "invalid actor_id")
			return
		}
		f.ActorID = id
	}
	if v := r.URL.Query().Get("before"); v != "" {
		id, err := strconv.ParseInt(v, 10, 64)
		if err != nil {
			writeErr(w, http.StatusBadRequest, "invalid before")
			return
		}
		f.BeforeID = id
	}
	if v := r.URL.Query().Get("limit"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil {
			writeErr(w, http.StatusBadRequest, "invalid limit")
			return
		}
		f.Limit = n
	}
	entries, err := a.db.ListAudit(f)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "could not list audit log")
		return
	}
	writeJSON(w, http.StatusOK, entries)
}
