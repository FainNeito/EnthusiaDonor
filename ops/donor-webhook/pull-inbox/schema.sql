-- Preparation schema v2. Fresh local databases only. No live migration executed.
CREATE TABLE inbox (
    source TEXT NOT NULL,
    id TEXT NOT NULL,
    body BLOB,
    signature TEXT,
    state TEXT NOT NULL DEFAULT 'pending' CHECK(state IN ('pending','quarantined','done')),
    created_at INTEGER NOT NULL DEFAULT (unixepoch()),
    lease_token TEXT,
    lease_until INTEGER NOT NULL DEFAULT 0,
    attempts INTEGER NOT NULL DEFAULT 0,
    completed_at INTEGER,
    quarantined_at INTEGER,
    quarantine_count INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY(source,id),
    CHECK(state = 'done' OR (body IS NOT NULL AND signature IS NOT NULL)),
    CHECK(state != 'done' OR (body IS NULL AND signature IS NULL AND completed_at IS NOT NULL)),
    CHECK(state != 'quarantined' OR (quarantined_at IS NOT NULL AND lease_token IS NULL)),
    CHECK(length(body) <= 262144)
);
CREATE INDEX inbox_pending ON inbox(source,state,lease_until,created_at,id);
CREATE INDEX inbox_completed ON inbox(source,state,completed_at,id);
