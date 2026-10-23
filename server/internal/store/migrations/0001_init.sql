-- All timestamps are unix seconds (UTC).
CREATE TABLE users (
    id            TEXT    PRIMARY KEY,
    username      TEXT    NOT NULL UNIQUE,
    password_hash TEXT    NOT NULL,
    seq           INTEGER NOT NULL DEFAULT 0, -- highest seq assigned to this user's records
    created_at    INTEGER NOT NULL
) STRICT;

CREATE TABLE devices (
    id           TEXT    PRIMARY KEY,
    user_id      TEXT    NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name         TEXT    NOT NULL,
    created_at   INTEGER NOT NULL,
    last_seen_at INTEGER NOT NULL,
    revoked_at   INTEGER            -- set on refresh-token reuse; device can no longer authenticate
) STRICT;
CREATE INDEX devices_user ON devices(user_id);

CREATE TABLE refresh_tokens (
    hash       TEXT    PRIMARY KEY,  -- hex SHA-256 of the token; the token itself is never stored
    device_id  TEXT    NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    expires_at INTEGER NOT NULL,
    rotated_at INTEGER,              -- non-NULL once exchanged; kept until expiry for reuse detection
    created_at INTEGER NOT NULL
) STRICT;
CREATE INDEX refresh_tokens_device ON refresh_tokens(device_id);
CREATE INDEX refresh_tokens_expires ON refresh_tokens(expires_at);

CREATE TABLE records (
    user_id    TEXT    NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    collection TEXT    NOT NULL,
    key        TEXT    NOT NULL,
    value      TEXT,                 -- compact JSON object text, NULL for null/tombstones
    deleted    INTEGER NOT NULL,
    clock      INTEGER NOT NULL,
    device     TEXT    NOT NULL,
    seq        INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (user_id, collection, key)
) STRICT, WITHOUT ROWID;
CREATE UNIQUE INDEX records_user_seq ON records(user_id, seq);
