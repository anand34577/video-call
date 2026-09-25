-- Marks accounts removed by an admin. Their messages stay in other people's
-- history under "Deleted user"; the account can never sign in or be
-- re-enabled. Earlier releases only renamed such accounts, so backfill them.
ALTER TABLE users ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0;
UPDATE users SET deleted = 1, role = 'user' WHERE display_name = 'Deleted user' AND username LIKE 'deleted-user-%';
