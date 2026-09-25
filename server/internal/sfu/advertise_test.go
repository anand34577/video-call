package sfu

import (
	"fmt"
	"net"
	"strings"
	"testing"
	"time"

	"github.com/pion/webrtc/v4"
)

// A peer must advertise the address the browser used, on the one shared UDP
// port, so group calls work through `docker run -p 7882:7882/udp`.
func TestPeerAdvertisesBrowserAddressOnSharedPort(t *testing.T) {
	l, err := net.ListenUDP("udp", &net.UDPAddr{})
	if err != nil {
		t.Fatal(err)
	}
	port := l.LocalAddr().(*net.UDPAddr).Port
	l.Close()

	e := NewEngine(Config{MaxParticipants: 8, UDPPort: port, FallbackIP: "10.9.9.9"})
	defer e.Close()
	if e.udpMux == nil {
		t.Fatalf("UDP port %d was not bound", port)
	}

	for _, tc := range []struct{ advertise, want string }{
		{"192.0.2.10", "192.0.2.10"}, // the browser's address wins
		{"", "10.9.9.9"},             // unknown address falls back
	} {
		sdp := gatherOffer(t, e, tc.advertise)
		want := fmt.Sprintf("%s %d typ host", tc.want, port)
		if !strings.Contains(sdp, want) {
			t.Errorf("advertise %q: SDP has no candidate %q:\n%s", tc.advertise, want, sdp)
		}
	}
}

func gatherOffer(t *testing.T, e *Engine, advertiseIP string) string {
	t.Helper()
	pc, err := e.newPeer(advertiseIP)
	if err != nil {
		t.Fatal(err)
	}
	defer pc.Close()
	if _, err := pc.AddTransceiverFromKind(webrtc.RTPCodecTypeAudio); err != nil {
		t.Fatal(err)
	}
	offer, err := pc.CreateOffer(nil)
	if err != nil {
		t.Fatal(err)
	}
	done := webrtc.GatheringCompletePromise(pc)
	if err := pc.SetLocalDescription(offer); err != nil {
		t.Fatal(err)
	}
	select {
	case <-done:
	case <-time.After(10 * time.Second):
		t.Fatal("ICE gathering timed out")
	}
	return pc.LocalDescription().SDP
}
