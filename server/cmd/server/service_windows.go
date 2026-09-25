//go:build windows

package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"

	"golang.org/x/sys/windows/svc"
)

var isService bool

// setupService detects a Service Control Manager launch (see
// scripts/install-service.ps1). Services start in System32 with no console,
// so chdir next to the exe (where .env and ./data live) and send logs to
// videocall.log there.
// The log file is never rotated, so trim it by hand if it gets large.
func setupService() {
	ok, err := svc.IsWindowsService()
	if err != nil || !ok {
		return
	}
	isService = true
	exe, err := os.Executable()
	if err != nil {
		return
	}
	dir := filepath.Dir(exe)
	_ = os.Chdir(dir)
	if f, err := os.OpenFile(filepath.Join(dir, "videocall.log"), os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644); err == nil {
		os.Stdout, os.Stderr = f, f
		log.SetOutput(f)
	}
}

// shutdownContext is cancelled on Ctrl+C, or on an SCM stop when running as
// a service. The returned func must run after shutdown finishes: it tells
// the SCM the service has stopped.
func shutdownContext() (context.Context, func()) {
	if !isService {
		return signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	}
	ctx, cancel := context.WithCancel(context.Background())
	h := &svcHandler{cancel: cancel, done: make(chan struct{})}
	exited := make(chan struct{})
	go func() {
		_ = svc.Run("VisionCall", h)
		cancel()
		close(exited)
	}()
	return ctx, func() { close(h.done); <-exited }
}

type svcHandler struct {
	cancel context.CancelFunc
	done   chan struct{}
}

func (h *svcHandler) Execute(_ []string, r <-chan svc.ChangeRequest, s chan<- svc.Status) (bool, uint32) {
	s <- svc.Status{State: svc.Running, Accepts: svc.AcceptStop | svc.AcceptShutdown}
	for c := range r {
		switch c.Cmd {
		case svc.Interrogate:
			s <- c.CurrentStatus
		case svc.Stop, svc.Shutdown:
			s <- svc.Status{State: svc.StopPending}
			h.cancel()
			<-h.done
			return false, 0
		}
	}
	return false, 0
}
