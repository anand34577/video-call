CREATE TABLE app_settings (
  setting_key TEXT PRIMARY KEY,
  value TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  updated_by BIGINT REFERENCES users(id) ON DELETE SET NULL
);
