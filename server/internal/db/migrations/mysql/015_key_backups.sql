-- One password-protected encryption key backup per account (see
-- api/key_backup_handlers.go). The server can't read it.
CREATE TABLE key_backups (
  user_id BIGINT NOT NULL PRIMARY KEY,
  data TEXT NOT NULL,
  updated_at VARCHAR(64) NOT NULL,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;
