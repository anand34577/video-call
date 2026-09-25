-- Soft-delete support for messages: the row (and its id/ordering) stays, but
-- content and any attachment are cleared so deleting a message doesn't erase
-- the conversation history around it.
ALTER TABLE messages ADD COLUMN deleted_at TEXT;

-- Per-uploader storage accounting for upload quotas.
CREATE INDEX idx_files_uploader ON files(uploader_id);
