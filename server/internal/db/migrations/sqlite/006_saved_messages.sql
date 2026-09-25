-- Personal message bookmarks — independent of pin (which is shared with the
-- whole conversation). Only the saving user ever sees their saved list.
CREATE TABLE saved_messages (
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  message_id INTEGER NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
  created_at TEXT NOT NULL,
  PRIMARY KEY (user_id, message_id)
);
CREATE INDEX idx_saved_messages_user ON saved_messages(user_id, created_at);
