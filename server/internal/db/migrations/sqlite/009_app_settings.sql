-- Admin-editable runtime settings (the "Settings" screen). Only ever
-- consulted for a key that isn't already pinned by an environment variable
-- or .env file — see internal/settings for the full priority chain.
-- Note: setting_key, not "key" — key is a reserved word in MySQL and
-- would need per-dialect quoting everywhere it's referenced otherwise.
CREATE TABLE app_settings (
  setting_key TEXT PRIMARY KEY,
  value TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  updated_by INTEGER REFERENCES users(id) ON DELETE SET NULL
);
