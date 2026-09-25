-- OIDC/SSO account linking. A user is "linked" once both columns are set.
-- The (issuer, subject) pair is the external identity's true unique key —
-- email is not, since providers don't guarantee it's unique or even present.
ALTER TABLE users ADD COLUMN oidc_issuer TEXT;
ALTER TABLE users ADD COLUMN oidc_subject TEXT;
CREATE UNIQUE INDEX idx_users_oidc ON users(oidc_issuer, oidc_subject);
