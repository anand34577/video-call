CREATE TABLE user_devices (
  user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  device_id TEXT NOT NULL,
  public_key_jwk TEXT NOT NULL,
  created_at TEXT NOT NULL,
  PRIMARY KEY (user_id, device_id)
);

ALTER TABLE messages ADD COLUMN is_encrypted INTEGER NOT NULL DEFAULT 0;
ALTER TABLE messages ADD COLUMN enc_iv TEXT;
ALTER TABLE messages ADD COLUMN enc_keys TEXT;
