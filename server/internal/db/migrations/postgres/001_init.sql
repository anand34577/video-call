CREATE TABLE users (
  id BIGSERIAL PRIMARY KEY,
  username TEXT NOT NULL UNIQUE,
  display_name TEXT NOT NULL,
  password_hash TEXT NOT NULL,
  role TEXT NOT NULL DEFAULT 'user' CHECK (role IN ('admin','user')),
  avatar_file_id BIGINT,
  disabled INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL
);

CREATE TABLE sessions (
  id TEXT PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash TEXT NOT NULL,
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL
);
CREATE INDEX idx_sessions_user ON sessions(user_id);

CREATE TABLE files (
  id BIGSERIAL PRIMARY KEY,
  uploader_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  mime TEXT NOT NULL,
  size BIGINT NOT NULL,
  path TEXT NOT NULL,
  created_at TEXT NOT NULL
);

ALTER TABLE users ADD CONSTRAINT fk_users_avatar FOREIGN KEY (avatar_file_id) REFERENCES files(id) ON DELETE SET NULL;

CREATE TABLE groups (
  id BIGSERIAL PRIMARY KEY,
  name TEXT NOT NULL,
  created_by BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at TEXT NOT NULL
);

CREATE TABLE group_members (
  group_id BIGINT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  role TEXT NOT NULL DEFAULT 'member',
  PRIMARY KEY (group_id, user_id)
);
CREATE INDEX idx_group_members_user ON group_members(user_id);

CREATE TABLE messages (
  id BIGSERIAL PRIMARY KEY,
  sender_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  recipient_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
  group_id BIGINT REFERENCES groups(id) ON DELETE CASCADE,
  file_id BIGINT REFERENCES files(id) ON DELETE SET NULL,
  content TEXT NOT NULL DEFAULT '',
  sent_at TEXT NOT NULL,
  delivered_at TEXT,
  read_at TEXT
);
CREATE INDEX idx_messages_sender ON messages(sender_id, id);
CREATE INDEX idx_messages_recipient ON messages(recipient_id, id);
CREATE INDEX idx_messages_group ON messages(group_id, id);
CREATE INDEX idx_messages_undelivered ON messages(recipient_id) WHERE delivered_at IS NULL;

CREATE TABLE calls (
  id BIGSERIAL PRIMARY KEY,
  room_id TEXT NOT NULL DEFAULT '',
  initiator_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  is_conference INTEGER NOT NULL DEFAULT 0,
  started_at TEXT NOT NULL,
  ended_at TEXT
);
CREATE INDEX idx_calls_initiator ON calls(initiator_id);

CREATE TABLE call_participants (
  call_id BIGINT NOT NULL REFERENCES calls(id) ON DELETE CASCADE,
  user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  joined_at TEXT,
  left_at TEXT,
  missed INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (call_id, user_id)
);
CREATE INDEX idx_call_participants_user ON call_participants(user_id);
