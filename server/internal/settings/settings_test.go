package settings

import (
	"os"
	"testing"

	"visioncall/internal/config"
	"visioncall/internal/db"
)

func openTestDB(t *testing.T) *db.DB {
	t.Helper()
	d, err := db.Open("sqlite", ":memory:")
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	t.Cleanup(func() { d.Close() })
	return d
}

// TestPriorityChain is the self-check for the whole point of this package:
// env beats DB beats hardcoded default.
func TestPriorityChain(t *testing.T) {
	dbh := openTestDB(t)
	admin, err := dbh.CreateUser("admin", "Admin", "hash", "admin")
	if err != nil {
		t.Fatalf("CreateUser: %v", err)
	}
	cfg := &config.Config{MaxCallParticipants: 8} // the hardcoded/env-resolved fallback
	s := New(dbh, cfg, nil)

	if got := s.Get().MaxCallParticipants; got != 8 {
		t.Fatalf("expected default 8, got %d", got)
	}

	// Settings-screen write takes effect over the default.
	if err := s.Set("MAX_CALL_PARTICIPANTS", "20", admin.ID); err != nil {
		t.Fatalf("Set: %v", err)
	}
	if got := s.Get().MaxCallParticipants; got != 20 {
		t.Fatalf("expected DB override 20, got %d", got)
	}
	if src := s.sourceOfDynamic("MAX_CALL_PARTICIPANTS"); src != "db" {
		t.Fatalf("expected source db, got %q", src)
	}

	// An environment variable set for the same key beats the DB value —
	// simulated here since the real env is already snapshotted at New().
	os.Setenv("MAX_CALL_PARTICIPANTS", "5")
	defer os.Unsetenv("MAX_CALL_PARTICIPANTS")
	s2 := New(dbh, cfg, nil) // fresh snapshot, as a process restart with the env var set would produce
	if got := s2.Get().MaxCallParticipants; got != 5 {
		t.Fatalf("expected env override 5, got %d", got)
	}
	if err := s2.Set("MAX_CALL_PARTICIPANTS", "99", admin.ID); err == nil {
		t.Fatal("Set should refuse to override an env-pinned key")
	}

	// Reset falls back past the DB override to the default.
	if err := s.Reset("MAX_CALL_PARTICIPANTS"); err != nil {
		t.Fatalf("Reset: %v", err)
	}
	if got := s.Get().MaxCallParticipants; got != 8 {
		t.Fatalf("expected default 8 after reset, got %d", got)
	}
}

func TestSetRejectsStaticAndUnknownKeys(t *testing.T) {
	dbh := openTestDB(t)
	s := New(dbh, &config.Config{}, nil)
	if err := s.Set("LISTEN_ADDR", ":9999", 1); err == nil {
		t.Fatal("Set should refuse a static (non-dynamic) key")
	}
	if err := s.Set("NOT_A_REAL_KEY", "x", 1); err == nil {
		t.Fatal("Set should refuse an unknown key")
	}
}

func TestListMasksSecrets(t *testing.T) {
	dbh := openTestDB(t)
	admin, err := dbh.CreateUser("admin", "Admin", "hash", "admin")
	if err != nil {
		t.Fatalf("CreateUser: %v", err)
	}
	s := New(dbh, &config.Config{}, nil)
	if err := s.Set("TURN_SECRET", "super-secret-value", admin.ID); err != nil {
		t.Fatalf("Set: %v", err)
	}
	for _, v := range s.List() {
		if v.Key == "TURN_SECRET" {
			if v.Value == "super-secret-value" {
				t.Fatal("secret value should be masked in List()")
			}
			return
		}
	}
	t.Fatal("TURN_SECRET not found in List()")
}

// TestIntRangesRejectLockout: out-of-range values are refused on write and
// ignored on read, so e.g. SESSION_TTL_HOURS=0 can't lock everyone out.
func TestIntRangesRejectLockout(t *testing.T) {
	dbh := openTestDB(t)
	admin, _ := dbh.CreateUser("admin", "Admin", "hash", "admin")
	s := New(dbh, &config.Config{SessionTTLHours: 12, MaxFileBytes: 50 << 20}, nil)
	if err := s.Set("SESSION_TTL_HOURS", "0", admin.ID); err == nil {
		t.Fatal("SESSION_TTL_HOURS=0 must be rejected")
	}
	if err := s.Set("MAX_FILE_MB", "-5", admin.ID); err == nil {
		t.Fatal("negative MAX_FILE_MB must be rejected")
	}
	// A bad value already stored (saved before validation existed).
	if err := dbh.SetAppSetting("SESSION_TTL_HOURS", "0", nil); err != nil {
		t.Fatal(err)
	}
	s.Reload()
	if got := s.Get().SessionTTLHours; got != 12 {
		t.Fatalf("stored 0 must fall back to the default 12, got %d", got)
	}
}
