package sfu

import (
	"testing"

	"github.com/pion/webrtc/v4"
)

// TestForceMuteMicAndRestore is the regression check for host-mute enforcement:
// forceMuteMic must pull the mic track out of the room's active forwarding
// set (so a modified client that ignores "room:muted" still goes silent to
// everyone else), and restoreMic must be able to bring the very same track
// back without needing a fresh publisher renegotiation.
func TestForceMuteMicAndRestore(t *testing.T) {
	e := NewEngine(Config{MaxParticipants: 8})
	if _, err := e.Join("group:1", 1, "a", nil, true, false, "", noopSignal); err != nil {
		t.Fatalf("join 1: %v", err)
	}
	if _, err := e.Join("group:1", 2, "b", nil, true, false, "", noopSignal); err != nil {
		t.Fatalf("join 2: %v", err)
	}
	room := e.rooms["group:1"]
	if room == nil {
		t.Fatal("room missing")
	}

	local, err := webrtc.NewTrackLocalStaticRTP(webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeOpus}, "mic", "1")
	if err != nil {
		t.Fatalf("NewTrackLocalStaticRTP: %v", err)
	}
	p1 := room.participant(1)
	pt := &publishedTrack{local: local, key: "mic"}
	room.publish(p1, "mic", pt)

	room.mu.Lock()
	if room.tracks[1]["mic"] == nil {
		room.mu.Unlock()
		t.Fatal("mic should be published before muting")
	}
	room.mu.Unlock()

	room.forceMuteMic(1)

	room.mu.Lock()
	if room.tracks[1]["mic"] != nil {
		room.mu.Unlock()
		t.Fatal("forceMuteMic should remove the mic from active tracks")
	}
	if room.mutedTracks[1]["mic"] != pt {
		room.mu.Unlock()
		t.Fatal("forceMuteMic should stash the same track for later restore")
	}
	room.mu.Unlock()

	// Calling it again (e.g. a duplicate mute request) must not panic or lose state.
	room.forceMuteMic(1)

	room.restoreMic(1)

	room.mu.Lock()
	defer room.mu.Unlock()
	if room.tracks[1]["mic"] != pt {
		t.Fatal("restoreMic should bring the exact same track back into active tracks")
	}
	if _, stillMuted := room.mutedTracks[1]["mic"]; stillMuted {
		t.Fatal("restoreMic should clear the muted-track stash")
	}
}
