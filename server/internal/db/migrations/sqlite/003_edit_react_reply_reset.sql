-- Message editing and reply-to.
ALTER TABLE messages ADD COLUMN edited_at TEXT;
ALTER TABLE messages ADD COLUMN reply_to_id INTEGER REFERENCES messages(id) ON DELETE SET NULL;

-- Emoji reactions. A user may react to the same message with several
-- distinct emoji, but only once each (re-reacting with the same emoji toggles
-- it off — see DB.ToggleReaction).
CREATE TABLE message_reactions (
  message_id INTEGER NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  emoji TEXT NOT NULL,
  created_at TEXT NOT NULL,
  PRIMARY KEY (message_id, user_id, emoji)
);
CREATE INDEX idx_reactions_message ON message_reactions(message_id);

-- Optional email, for self-service password reset. Not required to use the
-- app; only users with one on file can request a reset.
ALTER TABLE users ADD COLUMN email TEXT;
CREATE UNIQUE INDEX idx_users_email ON users(email) WHERE email IS NOT NULL;

-- Password reset tokens (hashed at rest, like sessions.token_hash).
CREATE TABLE password_reset_tokens (
  id TEXT PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash TEXT NOT NULL,
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  used_at TEXT
);
CREATE INDEX idx_reset_tokens_user ON password_reset_tokens(user_id);

-- Per-user last-read marker for group chats, mirroring what messages.read_at
-- already gives 1:1 chats, so a fresh login can show accurate group unread
-- counts instead of only what arrived while connected.
CREATE TABLE group_read_state (
  group_id INTEGER NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  last_read_message_id INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (group_id, user_id)
);
