-- Preparation schema v5. Fresh local databases only. No live migration executed.
CREATE TABLE inbox (
    source TEXT NOT NULL,
    id TEXT NOT NULL,
    body BLOB,
    signature TEXT,
    state TEXT NOT NULL DEFAULT 'pending' CHECK(state IN ('pending','quarantined','done')),
    created_at INTEGER NOT NULL DEFAULT (unixepoch()),
    lease_token TEXT,
    lease_until INTEGER NOT NULL DEFAULT 0,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK(typeof(attempts)='integer' AND attempts BETWEEN 0 AND 20),
    completed_at INTEGER,
    quarantined_at INTEGER,
    quarantine_count INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY(source,id),
    CHECK(state = 'done' OR (body IS NOT NULL AND signature IS NOT NULL)),
    CHECK(state != 'done' OR (body IS NULL AND signature IS NULL AND completed_at IS NOT NULL)),
    CHECK(state != 'quarantined' OR (quarantined_at IS NOT NULL AND lease_token IS NULL)),
    CHECK(length(body) <= 262144)
);
CREATE INDEX inbox_completed ON inbox(source,completed_at,id) WHERE state='done';

-- Singleton accounting is maintained transactionally by the triggers below.
CREATE TABLE inbox_usage (
    singleton INTEGER PRIMARY KEY CHECK(singleton=1),
    rows INTEGER NOT NULL CHECK(rows>=0),
    payload_bytes INTEGER NOT NULL CHECK(payload_bytes>=0)
);
INSERT INTO inbox_usage VALUES(1,0,0);
CREATE INDEX inbox_attempts ON inbox(source,attempts,lease_until,created_at,id) WHERE state='pending';

CREATE TABLE inbox_source_usage (
    source TEXT PRIMARY KEY,
    pending INTEGER NOT NULL CHECK(pending>=0),
    quarantined INTEGER NOT NULL CHECK(quarantined>=0),
    completed INTEGER NOT NULL CHECK(completed>=0)
);
CREATE INDEX inbox_source_age ON inbox(source,state,created_at);

-- TRIGGERS START
-- These triggers are mandatory; apply the entire schema before any inbox writes.
CREATE TRIGGER inbox_usage_insert AFTER INSERT ON inbox BEGIN
  SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM inbox_usage WHERE singleton=1) THEN RAISE(ABORT,'missing inbox usage') END;
  UPDATE inbox_usage SET rows=rows+1, payload_bytes=payload_bytes+coalesce(length(NEW.body),0) WHERE singleton=1;
END;
-- trigger-break
CREATE TRIGGER inbox_usage_update AFTER UPDATE OF body ON inbox WHEN OLD.body IS NOT NEW.body BEGIN
  SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM inbox_usage WHERE singleton=1) THEN RAISE(ABORT,'missing inbox usage') END;
  UPDATE inbox_usage SET payload_bytes=payload_bytes-coalesce(length(OLD.body),0)+coalesce(length(NEW.body),0) WHERE singleton=1;
END;
-- trigger-break
CREATE TRIGGER inbox_usage_delete AFTER DELETE ON inbox BEGIN
  SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM inbox_usage WHERE singleton=1) THEN RAISE(ABORT,'missing inbox usage') END;
  UPDATE inbox_usage SET rows=rows-1, payload_bytes=payload_bytes-coalesce(length(OLD.body),0) WHERE singleton=1;
END;

-- trigger-break
CREATE TRIGGER inbox_source_insert AFTER INSERT ON inbox BEGIN
  SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM inbox_source_usage WHERE source=NEW.source)
    AND EXISTS(SELECT 1 FROM inbox WHERE source=NEW.source AND id!=NEW.id)
    THEN RAISE(ABORT,'missing source usage') END;
  INSERT INTO inbox_source_usage VALUES(NEW.source,NEW.state='pending',NEW.state='quarantined',NEW.state='done')
  ON CONFLICT(source) DO UPDATE SET pending=pending+(NEW.state='pending'),
    quarantined=quarantined+(NEW.state='quarantined'), completed=completed+(NEW.state='done');
END;
-- trigger-break
CREATE TRIGGER inbox_source_delete AFTER DELETE ON inbox BEGIN
  UPDATE inbox_source_usage SET pending=pending-(OLD.state='pending'),
    quarantined=quarantined-(OLD.state='quarantined'), completed=completed-(OLD.state='done') WHERE source=OLD.source;
  SELECT CASE WHEN changes()!=1 THEN RAISE(ABORT,'missing source usage') END;
END;
-- trigger-break
CREATE TRIGGER inbox_source_update AFTER UPDATE OF state,source ON inbox WHEN OLD.state!=NEW.state OR OLD.source!=NEW.source BEGIN
  SELECT CASE WHEN OLD.source!=NEW.source AND NOT EXISTS(SELECT 1 FROM inbox_source_usage WHERE source=NEW.source)
    AND EXISTS(SELECT 1 FROM inbox WHERE source=NEW.source AND id!=NEW.id)
    THEN RAISE(ABORT,'missing source usage') END;
  UPDATE inbox_source_usage SET pending=pending-(OLD.state='pending'),
    quarantined=quarantined-(OLD.state='quarantined'), completed=completed-(OLD.state='done') WHERE source=OLD.source;
  SELECT CASE WHEN changes()!=1 THEN RAISE(ABORT,'missing source usage') END;
  INSERT INTO inbox_source_usage VALUES(NEW.source,NEW.state='pending',NEW.state='quarantined',NEW.state='done')
  ON CONFLICT(source) DO UPDATE SET pending=pending+(NEW.state='pending'),
    quarantined=quarantined+(NEW.state='quarantined'), completed=completed+(NEW.state='done');
END;
