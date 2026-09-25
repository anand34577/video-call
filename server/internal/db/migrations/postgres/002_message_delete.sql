ALTER TABLE messages ADD COLUMN deleted_at TEXT;

CREATE INDEX idx_files_uploader ON files(uploader_id);
