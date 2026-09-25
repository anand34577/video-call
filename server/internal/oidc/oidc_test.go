package oidc

import (
	"net/url"
	"testing"

	"github.com/golang-jwt/jwt/v5"
	"golang.org/x/oauth2"
)

// newTestManager builds a Manager whose state-token logic (AuthURL) can be
// exercised without a live provider — Exchange needs real network discovery
// and a running IdP, so it's out of scope for a unit test.
func newTestManager(secret string) *Manager {
	return &Manager{
		ready:        true, // skip discovery — this test only exercises state-token logic
		oauth2Config: oauth2.Config{ClientID: "client", Endpoint: oauth2.Endpoint{AuthURL: "https://idp.example/authorize"}},
		jwtSecret:    []byte(secret),
	}
}

func TestAuthURLStateRoundTrip(t *testing.T) {
	m := newTestManager("secret")
	url, err := m.AuthURL(FlowLink, 42, "browser-binding")
	if err != nil {
		t.Fatalf("AuthURL: %v", err)
	}
	// Pull the "state" query param back out and verify it decodes to what
	// we put in — this is the entire security boundary for the link flow
	// (it's how a callback knows *which* account to attach the identity to).
	state := extractQueryParam(t, url, "state")
	var claims stateClaims
	tok, err := jwt.ParseWithClaims(state, &claims, func(*jwt.Token) (any, error) { return []byte("secret"), nil })
	if err != nil || !tok.Valid {
		t.Fatalf("state token should parse with the right secret: %v", err)
	}
	if claims.Flow != FlowLink || claims.LinkUserID != 42 {
		t.Fatalf("state claims wrong: %+v", claims)
	}
}

func TestStateTokenRejectsWrongSecret(t *testing.T) {
	m := newTestManager("secret-a")
	url, err := m.AuthURL(FlowLogin, 0, "browser-binding")
	if err != nil {
		t.Fatalf("AuthURL: %v", err)
	}
	state := extractQueryParam(t, url, "state")
	var claims stateClaims
	_, err = jwt.ParseWithClaims(state, &claims, func(*jwt.Token) (any, error) { return []byte("secret-b"), nil })
	if err == nil {
		t.Fatal("state token signed with a different secret should not verify")
	}
}

func extractQueryParam(t *testing.T, rawURL, key string) string {
	t.Helper()
	u, err := url.Parse(rawURL)
	if err != nil {
		t.Fatalf("parse url: %v", err)
	}
	v := u.Query().Get(key)
	if v == "" {
		t.Fatalf("missing %q in %s", key, rawURL)
	}
	return v
}
