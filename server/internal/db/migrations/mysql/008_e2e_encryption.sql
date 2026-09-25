CREATE TABLE user_devices (
  user_id BIGINT NOT NULL,
  device_id VARCHAR(191) NOT NULL,
  public_key_jwk TEXT NOT NULL,
  created_at TEXT NOT NULL,
  PRIMARY KEY (user_id, device_id),
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

ALTER TABLE messages ADD COLUMN is_encrypted INTEGER NOT NULL DEFAULT 0;
ALTER TABLE messages ADD COLUMN enc_iv TEXT;
ALTER TABLE messages ADD COLUMN enc_keys TEXT;
