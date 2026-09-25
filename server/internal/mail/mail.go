// Package mail sends plain-text email over SMTP using only the standard
// library — no external dependency for what a handful of lines covers.
// It exists solely to deliver password-reset links, so it stays minimal.
package mail

import (
	"crypto/tls"
	"fmt"
	"net"
	"net/smtp"
	"strings"
	"time"
)

// Config is the handful of SMTP fields Send needs — passed explicitly
// (rather than the whole app config) so callers can source it from
// wherever settings actually live (env, .env, or the admin Settings
// screen's DB-backed overrides) without this package caring which.
type Config struct {
	Host, User, Pass, From string
	Port                   int
}

// sendTimeout bounds the whole SMTP conversation; smtp.SendMail has none,
// so an unreachable relay used to hang its caller indefinitely.
const sendTimeout = 30 * time.Second

// Send delivers a plain-text email via the configured SMTP relay. Port 465
// uses implicit TLS (SMTPS); any other port upgrades with STARTTLS when the
// server offers it. Auth is skipped when cfg.User is empty (some internal
// relays don't require it).
func Send(cfg Config, to, subject, body string) error {
	if cfg.Host == "" {
		return fmt.Errorf("mail: SMTP not configured")
	}
	from := cfg.From
	if from == "" {
		from = cfg.User
	}
	if from == "" {
		return fmt.Errorf("mail: no From address configured (set SMTP_FROM or SMTP_USER)")
	}

	addr := net.JoinHostPort(cfg.Host, fmt.Sprintf("%d", cfg.Port))
	tlsCfg := &tls.Config{ServerName: cfg.Host}
	dialer := &net.Dialer{Timeout: 10 * time.Second}
	var conn net.Conn
	var err error
	if cfg.Port == 465 {
		conn, err = tls.DialWithDialer(dialer, "tcp", addr, tlsCfg)
	} else {
		conn, err = dialer.Dial("tcp", addr)
	}
	if err != nil {
		return err
	}
	_ = conn.SetDeadline(time.Now().Add(sendTimeout))
	c, err := smtp.NewClient(conn, cfg.Host)
	if err != nil {
		conn.Close()
		return err
	}
	defer c.Close()
	if cfg.Port != 465 {
		if ok, _ := c.Extension("STARTTLS"); ok {
			if err := c.StartTLS(tlsCfg); err != nil {
				return err
			}
		}
	}
	if cfg.User != "" {
		if err := c.Auth(smtp.PlainAuth("", cfg.User, cfg.Pass, cfg.Host)); err != nil {
			return err
		}
	}
	if err := c.Mail(from); err != nil {
		return err
	}
	if err := c.Rcpt(to); err != nil {
		return err
	}
	w, err := c.Data()
	if err != nil {
		return err
	}
	if _, err := w.Write(buildMessage(from, to, subject, body)); err != nil {
		return err
	}
	if err := w.Close(); err != nil {
		return err
	}
	return c.Quit()
}

func buildMessage(from, to, subject, body string) []byte {
	var b strings.Builder
	fmt.Fprintf(&b, "From: %s\r\n", from)
	fmt.Fprintf(&b, "To: %s\r\n", to)
	fmt.Fprintf(&b, "Subject: %s\r\n", subject)
	b.WriteString("MIME-Version: 1.0\r\n")
	b.WriteString("Content-Type: text/plain; charset=UTF-8\r\n")
	b.WriteString("\r\n")
	b.WriteString(body)
	return []byte(b.String())
}
