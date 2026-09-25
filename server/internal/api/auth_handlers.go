package api

import (
	"net/http"
	"strings"
	"time"

	"visioncall/internal/auth"
)

type loginRequest struct {
	Username string `json:"username"`
	Password string `json:"password"`
}

func (a *API) handleLogin(w http.ResponseWriter, r *http.Request) {
	var req loginRequest
	if err := readJSON(r, &req); err != nil {
		writeErr(w, http.StatusBadRequest, "invalid request body")
		return
	}
	req.Username = strings.TrimSpace(strings.ToLower(req.Username))
	if req.Username == "" || req.Password == "" {
		writeErr(w, http.StatusUnauthorized, "invalid username or password")
		return
	}
	ip := RealIP(r, a.settings.Get().TrustProxy)
	key := req.Username + "|" + ip
	// Both limiters must allow the attempt: the per-username key stops
	// guessing one account, the per-IP key stops a spray of a few guesses
	// each across many different usernames from one source, which the
	// per-username key alone never sees enough repetition to catch.
	// Reserve counts the attempt immediately, before the slow password
	// verification below, so a burst of concurrent requests can't all pass
	// the check before any of them gets counted.
	if !a.limiter.Reserve(key) || !a.ipLimiter.Reserve(ip) {
		writeErr(w, http.StatusTooManyRequests, "too many failed attempts; try again in a few minutes")
		return
	}
	user, err := a.db.GetUserByUsername(req.Username)
	if err != nil {
		// Spend the same argon2 cost as a real verification so this path
		// isn't distinguishable by timing from a wrong-password failure below.
		auth.VerifyDummyPassword(req.Password)
		a.auditAnon(r, req.Username, "login_failed", "unknown username")
		writeErr(w, http.StatusUnauthorized, "invalid username or password")
		return
	}
	ok, err := auth.VerifyPassword(user.PasswordHash, req.Password)
	if err != nil || !ok {
		a.auditAnon(r, req.Username, "login_failed", "bad password")
		writeErr(w, http.StatusUnauthorized, "invalid username or password")
		return
	}
	if user.Disabled {
		a.auditAnon(r, req.Username, "login_failed", "account disabled")
		writeErr(w, http.StatusForbidden, "This account is suspended. Please contact your administrator.")
		return
	}
	a.limiter.Success(key)
	a.ipLimiter.Refund(ip)

	ttl := time.Duration(a.settings.Get().SessionTTLHours) * time.Hour
	sessionID, token, err := auth.MintSession(a.cfg.JWTSecret, user.ID, user.Role, ttl)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "could not create session")
		return
	}
	if err := a.db.CreateSession(sessionID, user.ID, auth.HashToken(token), time.Now().Add(ttl)); err != nil {
		writeErr(w, http.StatusInternalServerError, "could not persist session")
		return
	}
	isSecure := a.isSecureRequest(r)

	http.SetCookie(w, &http.Cookie{
		Name:     auth.CookieName,
		Value:    token,
		Path:     "/",
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		Secure:   isSecure,
		MaxAge:   int(ttl.Seconds()),
	})
	auth.EnsureCSRFCookie(w, r, isSecure)
	if err := a.db.WriteAudit(&user.ID, user.Username, "login", "user", &user.ID, "", RealIP(r, a.settings.Get().TrustProxy)); err != nil && a.log != nil {
		a.log.Warn("write audit entry", "action", "login", "err", err)
	}
	user.PasswordHash = ""
	user.Status = "online"
	// Same shape as /me, so the client applies this account's saved theme
	// right away instead of whatever the previous user left on this browser.
	if prefs, err := a.db.GetUserPreferences(user.ID); err == nil {
		user.Preferences = prefs
	}
	writeJSON(w, http.StatusOK, user)
}

func (a *API) handleLogout(w http.ResponseWriter, r *http.Request) {
	a.audit(r, "logout", "user", nil, "")
	if cookie, err := r.Cookie(auth.CookieName); err == nil {
		if claims, err := auth.ParseToken(a.cfg.JWTSecret, cookie.Value); err == nil {
			a.db.DeleteSession(claims.SessionID)
		}
	}
	http.SetCookie(w, &http.Cookie{
		Name:     auth.CookieName,
		Value:    "",
		Path:     "/",
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		Secure:   a.isSecureRequest(r),
		MaxAge:   -1,
	})
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

func (a *API) handleMe(w http.ResponseWriter, r *http.Request) {
	user := auth.CurrentUser(r)
	user.PasswordHash = ""
	user.Status = "online"
	if prefs, err := a.db.GetUserPreferences(user.ID); err == nil {
		user.Preferences = prefs
	}
	writeJSON(w, http.StatusOK, user)
}
