CREATE TABLE IF NOT EXISTS user_preferences (
  user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  theme TEXT NOT NULL DEFAULT 'dark',
  accent_color TEXT NOT NULL DEFAULT 'blue',
  radius TEXT NOT NULL DEFAULT 'rounded',
  updated_at TEXT NOT NULL
);
