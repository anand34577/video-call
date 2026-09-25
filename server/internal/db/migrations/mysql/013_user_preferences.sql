CREATE TABLE IF NOT EXISTS user_preferences (
  user_id BIGINT PRIMARY KEY,
  theme VARCHAR(32) NOT NULL DEFAULT 'dark',
  accent_color VARCHAR(32) NOT NULL DEFAULT 'blue',
  radius VARCHAR(32) NOT NULL DEFAULT 'rounded',
  updated_at VARCHAR(32) NOT NULL,
  CONSTRAINT fk_user_preferences_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;
