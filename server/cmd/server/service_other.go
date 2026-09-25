//go:build !windows

package main

import (
	"context"
	"os"
	"os/signal"
	"syscall"
)

// systemd/Docker/launchd need nothing special: they just send SIGTERM.
func setupService() {}

func shutdownContext() (context.Context, func()) {
	return signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
}
