package ice

import (
	"crypto/hmac"
	"crypto/sha1"
	"encoding/base64"
	"strconv"
	"time"
)

// Credentials generates coturn "REST API" style ephemeral credentials from the
// shared static-auth-secret. Works fully offline.
func Credentials(secret string, ttl time.Duration) (username, password string) {
	username = strconv.FormatInt(time.Now().Add(ttl).Unix(), 10)
	mac := hmac.New(sha1.New, []byte(secret))
	mac.Write([]byte(username))
	password = base64.StdEncoding.EncodeToString(mac.Sum(nil))
	return
}
