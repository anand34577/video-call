-- sqlite and postgres already declare messages.content with DEFAULT '';
-- the original mysql migration (001_init.sql) dropped it. No current insert
-- path omits content, so this is a landmine fix, not a live-data repair.
ALTER TABLE messages MODIFY COLUMN content TEXT NOT NULL DEFAULT ('');
