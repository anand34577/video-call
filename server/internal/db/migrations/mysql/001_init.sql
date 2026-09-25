CREATE TABLE users (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(191) NOT NULL UNIQUE,
  display_name TEXT NOT NULL,
  password_hash TEXT NOT NULL,
  role VARCHAR(16) NOT NULL DEFAULT 'user' CHECK (role IN ('admin','user')),
  avatar_file_id BIGINT,
  disabled INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL
) ENGINE=InnoDB;

CREATE TABLE sessions (
  id VARCHAR(191) PRIMARY KEY,
  user_id BIGINT NOT NULL,
  token_hash TEXT NOT NULL,
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;
CREATE INDEX idx_sessions_user ON sessions(user_id);

CREATE TABLE files (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  uploader_id BIGINT NOT NULL,
  name TEXT NOT NULL,
  mime TEXT NOT NULL,
  size BIGINT NOT NULL,
  path TEXT NOT NULL,
  created_at TEXT NOT NULL,
  FOREIGN KEY (uploader_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

ALTER TABLE users ADD CONSTRAINT fk_users_avatar FOREIGN KEY (avatar_file_id) REFERENCES files(id) ON DELETE SET NULL;

CREATE TABLE `groups` (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name TEXT NOT NULL,
  created_by BIGINT NOT NULL,
  created_at TEXT NOT NULL,
  FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE group_members (
  group_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  role VARCHAR(16) NOT NULL DEFAULT 'member',
  PRIMARY KEY (group_id, user_id),
  FOREIGN KEY (group_id) REFERENCES `groups`(id) ON DELETE CASCADE,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;
CREATE INDEX idx_group_members_user ON group_members(user_id);

CREATE TABLE messages (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  sender_id BIGINT NOT NULL,
  recipient_id BIGINT,
  group_id BIGINT,
  file_id BIGINT,
  content TEXT NOT NULL,
  sent_at TEXT NOT NULL,
  delivered_at TEXT,
  read_at TEXT,
  FOREIGN KEY (sender_id) REFERENCES users(id) ON DELETE CASCADE,
  FOREIGN KEY (recipient_id) REFERENCES users(id) ON DELETE CASCADE,
  FOREIGN KEY (group_id) REFERENCES `groups`(id) ON DELETE CASCADE,
  FOREIGN KEY (file_id) REFERENCES files(id) ON DELETE SET NULL
) ENGINE=InnoDB;
CREATE INDEX idx_messages_sender ON messages(sender_id, id);
CREATE INDEX idx_messages_recipient ON messages(recipient_id, id);
CREATE INDEX idx_messages_group ON messages(group_id, id);
-- Note: MySQL has no partial indexes; index both columns instead of just
-- the undelivered subset. TEXT columns need a prefix length to be indexed.
CREATE INDEX idx_messages_undelivered ON messages(recipient_id, delivered_at(32));

CREATE TABLE calls (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  room_id TEXT NOT NULL,
  initiator_id BIGINT NOT NULL,
  is_conference INTEGER NOT NULL DEFAULT 0,
  started_at TEXT NOT NULL,
  ended_at TEXT,
  FOREIGN KEY (initiator_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;
CREATE INDEX idx_calls_initiator ON calls(initiator_id);

CREATE TABLE call_participants (
  call_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  joined_at TEXT,
  left_at TEXT,
  missed INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (call_id, user_id),
  FOREIGN KEY (call_id) REFERENCES calls(id) ON DELETE CASCADE,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;
CREATE INDEX idx_call_participants_user ON call_participants(user_id);
