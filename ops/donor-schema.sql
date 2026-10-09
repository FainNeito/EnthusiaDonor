-- Apply only to the approved dedicated donor database.
-- Retains existing data. Does not seed baseline, payments, events or receipts.
CREATE TABLE IF NOT EXISTS enthusiadonors_test_projection (
  source_id VARCHAR(64) PRIMARY KEY, revision BIGINT NOT NULL,
  owner_token VARCHAR(64), lease_until BIGINT NOT NULL,
  lease_epoch BIGINT NOT NULL, payload MEDIUMTEXT
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS enthusiadonors_test_events (
  source_id VARCHAR(64) NOT NULL, event_id VARCHAR(128) NOT NULL,
  created_at BIGINT NOT NULL, expires_at BIGINT NOT NULL,
  payload MEDIUMTEXT NOT NULL, PRIMARY KEY(source_id,event_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS enthusiadonors_test_receipts (
  source_id VARCHAR(64) NOT NULL, event_id VARCHAR(128) NOT NULL,
  proxy_id VARCHAR(64) NOT NULL, state VARCHAR(16) NOT NULL,
  updated_at BIGINT NOT NULL, PRIMARY KEY(source_id,event_id,proxy_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS enthusiadonors_notify_sources (
  source_id VARCHAR(64) PRIMARY KEY, baseline BIGINT NOT NULL
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS enthusiadonors_notify_seen (
  source_id VARCHAR(64) NOT NULL, payment_hash VARCHAR(128) NOT NULL,
  terminal INTEGER NOT NULL, PRIMARY KEY(source_id,payment_hash)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS enthusiadonors_notify_jobs (
  source_id VARCHAR(64) NOT NULL, payment_hash VARCHAR(128) NOT NULL,
  channel VARCHAR(16) NOT NULL, payload MEDIUMTEXT NOT NULL,
  state VARCHAR(16) NOT NULL, expires_at BIGINT NOT NULL,
  PRIMARY KEY(source_id,payment_hash,channel)
) ENGINE=InnoDB;
