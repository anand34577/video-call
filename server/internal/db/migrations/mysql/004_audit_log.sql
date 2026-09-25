CREATE TABLE audit_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  actor_id BIGINT,
  actor_name VARCHAR(191) NOT NULL DEFAULT '',
  action VARCHAR(64) NOT NULL,
  target_type VARCHAR(64) NOT NULL DEFAULT '',
  target_id BIGINT,
  detail TEXT,
  ip VARCHAR(64) NOT NULL DEFAULT '',
  created_at TEXT NOT NULL,
  FOREIGN KEY (actor_id) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB;
CREATE INDEX idx_audit_created ON audit_log(created_at(32));
CREATE INDEX idx_audit_actor ON audit_log(actor_id);
CREATE INDEX idx_audit_action ON audit_log(action);
