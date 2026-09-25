// Package logger provides a structured slog.Logger for the vision-call server.
package logger

import (
	"log/slog"
	"os"
	"strings"
)

// ParseLevel maps the app's string level names to slog.Level ("debug",
// "info", "warn"/"warning", "error", case-insensitive; default "info" for
// anything else, including empty).
func ParseLevel(level string) slog.Level {
	switch strings.ToLower(strings.TrimSpace(level)) {
	case "debug":
		return slog.LevelDebug
	case "warn", "warning":
		return slog.LevelWarn
	case "error":
		return slog.LevelError
	default:
		return slog.LevelInfo
	}
}

// New returns a text-format *slog.Logger writing to stdout, plus the
// *slog.LevelVar backing its level - callers can call SetLevel on it later
// to change verbosity at runtime (e.g. from the admin Settings screen)
// without restarting the process. AddSource is fixed at construction (tied
// to the *initial* level) since toggling it live would be a bigger change
// for a debug-only convenience.
func New(level string) (*slog.Logger, *slog.LevelVar) {
	lv := &slog.LevelVar{}
	initial := ParseLevel(level)
	lv.Set(initial)
	logger := slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{
		Level:     lv,
		AddSource: initial == slog.LevelDebug,
	}))
	return logger, lv
}
