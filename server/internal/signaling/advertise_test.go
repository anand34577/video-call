package signaling

import "testing"

func TestHostToIP(t *testing.T) {
	cases := map[string]string{
		"192.168.1.50:8443": "192.168.1.50",
		"192.168.1.50":      "192.168.1.50",
		"[fd00::5]:8443":    "fd00::5",
		"localhost:8443":    "127.0.0.1",
		"":                  "",
	}
	for in, want := range cases {
		if got := hostToIP(in); got != want {
			t.Errorf("hostToIP(%q) = %q, want %q", in, got, want)
		}
	}
}
