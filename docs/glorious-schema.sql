-- Review and provision separately; never executed automatically by this work-in-progress.
CREATE TABLE enthusiadonors_glorious_control (
    source_id VARCHAR(64) PRIMARY KEY,
    activated_at BIGINT NOT NULL,
    timezone VARCHAR(80) NOT NULL
) ENGINE=InnoDB;
CREATE TABLE enthusiadonors_glorious_awards (
    source_id VARCHAR(64) NOT NULL,
    month_key CHAR(7) NOT NULL,
    winner_uuid CHAR(36),
    state VARCHAR(16) NOT NULL,
    PRIMARY KEY (source_id, month_key)
) ENGINE=InnoDB;
CREATE TABLE enthusiadonors_glorious_payer_reviews (
    source_id VARCHAR(64) NOT NULL,
    month_key CHAR(7) NOT NULL,
    payment_hash CHAR(64) NOT NULL,
    payer_uuid CHAR(36) NOT NULL,
    reviewer_uuid CHAR(36) NOT NULL,
    evidence_digest CHAR(64) NOT NULL,
    reviewed_at BIGINT NOT NULL,
    PRIMARY KEY (source_id, payment_hash)
) ENGINE=InnoDB;
CREATE TABLE enthusiadonors_glorious_recoveries (
    source_id VARCHAR(64) NOT NULL,
    month_key CHAR(7) NOT NULL,
    winner_uuid CHAR(36) NOT NULL,
    reviewer_uuid CHAR(36) NOT NULL,
    evidence_digest CHAR(64) NOT NULL,
    resolved_at BIGINT NOT NULL,
    PRIMARY KEY (source_id, month_key)
) ENGINE=InnoDB;
