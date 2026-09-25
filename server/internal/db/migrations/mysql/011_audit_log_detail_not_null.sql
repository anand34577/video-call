-- sqlite and postgres declare audit_log.detail as TEXT NOT NULL DEFAULT '';
-- the original mysql migration (004_audit_log.sql) left it nullable with no
-- default. WriteAudit always supplies a value today, but ListAudit scans
-- straight into a Go string (not sql.NullString) - a future direct insert
-- that omits detail would fail that scan on MySQL only.
ALTER TABLE audit_log MODIFY COLUMN detail TEXT NOT NULL DEFAULT ('');
