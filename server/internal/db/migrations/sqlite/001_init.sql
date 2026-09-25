CREATE TABLE users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  username TEXT NOT NULL UNIQUE,
  display_name TEXT NOT NULL,
  password_hash TEXT NOT NULL,
  role TEXT NOT NULL DEFAULT 'user' CHECK (role IN ('admin','user')),
  avatar_file_id INTEGER REFERENCES files(id) ON DELETE SET NULL,
  disabled INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL
);

CREATE TABLE sessions (
  id TEXT PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash TEXT NOT NULL,
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL
);
CREATE INDEX idx_sessions_user ON sessions(user_id);

CREATE TABLE files (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  uploader_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  mime TEXT NOT NULL,
  size INTEGER NOT NULL,
  path TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE TABLE groups (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  created_by INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at TEXT NOT NULL
);

CREATE TABLE group_members (
  group_id INTEGER NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  role TEXT NOT NULL DEFAULT 'member',
  PRIMARY KEY (group_id, user_id)
);
CREATE INDEX idx_group_members_user ON group_members(user_id);

CREATE TABLE messages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  sender_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  recipient_id INTEGER REFERENCES users(id) ON DELETE CASCADE,
  group_id INTEGER REFERENCES groups(id) ON DELETE CASCADE,
  file_id INTEGER REFERENCES files(id) ON DELETE SET NULL,
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
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  room_id TEXT NOT NULL DEFAULT '',
  initiator_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  is_conference INTEGER NOT NULL DEFAULT 0,
  started_at TEXT NOT NULL,
  ended_at TEXT
);
CREATE INDEX idx_calls_initiator ON calls(initiator_id);

CREATE TABLE call_participants (
  call_id INTEGER NOT NULL REFERENCES calls(id) ON DELETE CASCADE,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  joined_at TEXT,
  left_at TEXT,
  missed INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (call_id, user_id)
);
CREATE INDEX idx_call_participants_user ON call_participants(user_id);
