-- Note: VARCHAR not TEXT — MySQL can't put a unique index on a TEXT
-- column without a prefix length, and these are just an issuer URL + an
-- opaque subject id, both comfortably short.
ALTER TABLE users ADD COLUMN oidc_issuer VARCHAR(255);
ALTER TABLE users ADD COLUMN oidc_subject VARCHAR(255);
CREATE UNIQUE INDEX idx_users_oidc ON users(oidc_issuer, oidc_subject);
