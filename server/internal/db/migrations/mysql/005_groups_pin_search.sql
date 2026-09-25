ALTER TABLE messages ADD COLUMN pinned_at TEXT;

-- Note: MySQL disallows a literal DEFAULT on TEXT/BLOB columns, so topic
-- is VARCHAR here (still plenty for a group topic) instead of TEXT.
ALTER TABLE `groups` ADD COLUMN topic VARCHAR(500) NOT NULL DEFAULT '';
ALTER TABLE `groups` ADD COLUMN avatar_file_id BIGINT;
ALTER TABLE `groups` ADD CONSTRAINT fk_groups_avatar FOREIGN KEY (avatar_file_id) REFERENCES files(id) ON DELETE SET NULL;
