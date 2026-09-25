package main

import (
	"crypto/tls"
	"net"
	"net/http"
	"time"
)

// healthcheck probes this server's own /api/healthz and returns the process
// exit code (0 healthy, 1 not). The Docker image runs it as its HEALTHCHECK.
//
// The main port speaks HTTPS or plain HTTP depending on settings that can
// also live in the database (TRUST_PROXY), so try both rather than guess.
// The certificate is usually self-signed, so it isn't verified: this only
// asks "is the server answering", from inside the same container.
func healthcheck(listenAddr string) int {
	_, port, err := net.SplitHostPort(listenAddr)
	if err != nil || port == "" {
		port = "8443"
	}
	client := &http.Client{
		Timeout: 3 * time.Second,
		Transport: &http.Transport{
			TLSClientConfig: &tls.Config{InsecureSkipVerify: true}, //nolint:gosec // local self-check only
		},
	}
	for _, scheme := range []string{"https", "http"} {
		resp, err := client.Get(scheme + "://127.0.0.1:" + port + "/api/healthz")
		if err != nil {
			continue
		}
		resp.Body.Close()
		if resp.StatusCode == http.StatusOK {
			return 0
		}
	}
	return 1
}
