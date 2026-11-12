-- Isolated protocol prototype. No live D1 database or migration is configured.
CREATE TABLE inbox (
    source TEXT NOT NULL,
    id TEXT NOT NULL,
    body BLOB,
    signature TEXT,
    state TEXT NOT NULL DEFAULT 'pending' CHECK(state IN ('pending','done')),
    created_at INTEGER NOT NULL DEFAULT (unixepoch()),
    lease_token TEXT,
    lease_until INTEGER NOT NULL DEFAULT 0,
    attempts INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY(source,id),
    CHECK(state != 'pending' OR (body IS NOT NULL AND signature IS NOT NULL)),
    CHECK(length(body) <= 262144)
);
CREATE INDEX inbox_pending ON inbox(source,state,lease_until,created_at,id);
