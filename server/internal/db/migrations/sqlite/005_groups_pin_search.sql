-- Pinned messages (works for both DMs and groups).
ALTER TABLE messages ADD COLUMN pinned_at TEXT;

-- Group topic/description and icon, so a group reads like a real "team"
-- space instead of just a name.
ALTER TABLE groups ADD COLUMN topic TEXT NOT NULL DEFAULT '';
ALTER TABLE groups ADD COLUMN avatar_file_id INTEGER REFERENCES files(id) ON DELETE SET NULL;
