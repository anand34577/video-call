-- One password-protected encryption key backup per account (see
-- api/key_backup_handlers.go). The server can't read it.
CREATE TABLE key_backups (
  user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  data TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
