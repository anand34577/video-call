-- Per-device ECDH public keys, for client-side end-to-end encryption. The
-- matching private key never leaves the browser that generated it (not
-- extractable, stored only in that device's IndexedDB) — the server only
-- ever holds public keys and encrypted blobs it cannot read.
CREATE TABLE user_devices (
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  device_id TEXT NOT NULL,
  public_key_jwk TEXT NOT NULL,
  created_at TEXT NOT NULL,
  PRIMARY KEY (user_id, device_id)
);

-- Encrypted messages store ciphertext in `content` (already TEXT, already
-- base64-safe) instead of plaintext. enc_keys is a JSON array of per-device
-- wrapped content keys: [{"user_id":.., "device_id":"..", "wrapped_key":"..",
-- "wrap_iv":"..", "sender_pub_jwk":{...}}, ...] — one entry per device that
-- can decrypt this specific message (every recipient's every registered
-- device, plus the sender's own other devices).
ALTER TABLE messages ADD COLUMN is_encrypted INTEGER NOT NULL DEFAULT 0;
ALTER TABLE messages ADD COLUMN enc_iv TEXT;
ALTER TABLE messages ADD COLUMN enc_keys TEXT;
