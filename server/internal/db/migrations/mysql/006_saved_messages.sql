CREATE TABLE saved_messages (
  user_id BIGINT NOT NULL,
  message_id BIGINT NOT NULL,
  created_at TEXT NOT NULL,
  PRIMARY KEY (user_id, message_id),
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  FOREIGN KEY (message_id) REFERENCES messages(id) ON DELETE CASCADE
) ENGINE=InnoDB;
CREATE INDEX idx_saved_messages_user ON saved_messages(user_id, created_at(32));
