package sfu

import "testing"

// TestMuteLockBlocksSelfUnmute checks that MuteRequest(lock=true) actually
// prevents the participant from unmuting themselves via onTrackState (the
// normal self-unmute path) until UnlockRequest clears the lock — the
// difference between a locked mute and today's plain force-mute, which a
// participant can always undo themselves.
func TestMuteLockBlocksSelfUnmute(t *testing.T) {
	e := NewEngine(Config{MaxParticipants: 8})
	if _, err := e.Join("group:1", 1, "host", nil, true, false, noopSignal); err != nil {
		t.Fatalf("join host: %v", err)
	}
	if _, err := e.Join("group:1", 2, "guest", nil, true, false, noopSignal); err != nil {
		t.Fatalf("join guest: %v", err)
	}
	if !e.MuteRequest(1, 2, false, true) {
		t.Fatal("host mute+lock should succeed")
	}
	guest := e.lookup(2)
	if !guest.infoSnapshot().Locked {
		t.Fatal("guest should be marked locked")
	}

	// Guest tries to self-unmute — must be refused.
	guest.onTrackState(TrackState{Muted: false, VideoOn: true})
	if !guest.infoSnapshot().Muted {
		t.Fatal("a locked mute must not be undoable by the participant's own unmute request")
	}

	// A bystander (not the host) can't unlock.
	if e.UnlockRequest(2, 2, false) {
		t.Fatal("a non-host must not be able to unlock a mute")
	}

	if !e.UnlockRequest(1, 2, false) {
		t.Fatal("host unlock should succeed")
	}
	if guest.infoSnapshot().Locked {
		t.Fatal("unlock should clear the locked flag")
	}
	// Now the self-unmute goes through.
	guest.onTrackState(TrackState{Muted: false, VideoOn: true})
	if guest.infoSnapshot().Muted {
		t.Fatal("self-unmute should succeed once unlocked")
	}
}

// TestPresenterOnlyBlocksNonPresenter checks that turning on presenter-only
// mode stops a non-host, non-approved participant's screen-share
// announcement from ever setting screenPending (which is what would let
// onTrack forward their next video track as "screen"), and that granting
// them presenter rights lifts the restriction.
func TestPresenterOnlyBlocksNonPresenter(t *testing.T) {
	e := NewEngine(Config{MaxParticipants: 8})
	if _, err := e.Join("group:1", 1, "host", nil, true, false, noopSignal); err != nil {
		t.Fatalf("join host: %v", err)
	}
	if _, err := e.Join("group:1", 2, "guest", nil, true, false, noopSignal); err != nil {
		t.Fatalf("join guest: %v", err)
	}
	if !e.SetPresenterOnly(1, true, false) {
		t.Fatal("host should be able to turn on presenter-only mode")
	}
	guest := e.lookup(2)
	if guest.infoSnapshot().CanPresent {
		t.Fatal("guest should lose CanPresent once presenter-only mode is on")
	}

	guest.onTrackState(TrackState{Muted: false, VideoOn: true, Screen: true})
	guest.screenMu.Lock()
	pending := guest.screenPending
	guest.screenMu.Unlock()
	if pending {
		t.Fatal("a non-presenter's screen announcement must not arm screenPending")
	}

	if !e.SetPresenter(1, 2, true, false) {
		t.Fatal("host should be able to grant presenter rights")
	}
	if !guest.infoSnapshot().CanPresent {
		t.Fatal("guest should regain CanPresent once granted")
	}
	guest.onTrackState(TrackState{Muted: false, VideoOn: true, Screen: true})
	guest.screenMu.Lock()
	pending = guest.screenPending
	guest.screenMu.Unlock()
	if !pending {
		t.Fatal("an approved presenter's screen announcement should arm screenPending")
	}

	host := e.lookup(1)
	if !host.infoSnapshot().CanPresent {
		t.Fatal("the host must always be able to present regardless of presenter-only mode")
	}
}
