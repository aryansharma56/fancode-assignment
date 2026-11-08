-- Matches the client API knows about. The id is the client-facing match id.
CREATE TABLE matches (
    id          TEXT PRIMARY KEY,
    title       TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- How each provider's match id maps to our client-facing id.
CREATE TABLE provider_match_map (
    provider          TEXT NOT NULL,
    provider_match_id TEXT NOT NULL,
    match_id          TEXT NOT NULL REFERENCES matches (id),
    PRIMARY KEY (provider, provider_match_id),
    UNIQUE (match_id, provider)
);

-- Latest applied score snapshot, one row per (match, provider).
-- Rows are only ever replaced by a strictly newer (sequence, revision) tuple.
CREATE TABLE provider_snapshots (
    match_id     TEXT        NOT NULL REFERENCES matches (id),
    provider     TEXT        NOT NULL,
    sequence     BIGINT      NOT NULL,
    revision     INT         NOT NULL,
    status       TEXT        NOT NULL,
    innings      INT         NOT NULL,
    runs         INT         NOT NULL,
    wickets      INT         NOT NULL,
    overs        TEXT        NOT NULL,
    source_time  TIMESTAMPTZ,            -- occurredAt / asOf; informational only
    received_at  TIMESTAMPTZ NOT NULL,   -- when we applied this tuple
    PRIMARY KEY (match_id, provider)
);

-- One row per logical ball per provider. Text is replaced only by a higher revision.
CREATE TABLE commentary (
    match_id     TEXT        NOT NULL REFERENCES matches (id),
    provider     TEXT        NOT NULL,
    ball_id      TEXT        NOT NULL,
    sequence     BIGINT      NOT NULL,
    revision     INT         NOT NULL,
    innings      INT,
    over_no      INT,
    ball_no      INT,
    text         TEXT        NOT NULL,
    occurred_at  TIMESTAMPTZ,
    updated_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (match_id, provider, ball_id)
);

CREATE INDEX commentary_display_order ON commentary (match_id, provider, sequence, ball_id);

-- Push idempotency: a (provider, deliveryId) is recorded in the same transaction
-- as its effects, so a retried delivery is recognised and skipped.
CREATE TABLE processed_deliveries (
    provider      TEXT        NOT NULL,
    delivery_id   TEXT        NOT NULL,
    payload_hash  TEXT        NOT NULL,
    received_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (provider, delivery_id)
);

-- Ingestion health per provider. Errors are recorded here and never touch the data tables.
CREATE TABLE provider_health (
    match_id         TEXT NOT NULL REFERENCES matches (id),
    provider         TEXT NOT NULL,
    last_success_at  TIMESTAMPTZ,
    last_error_at    TIMESTAMPTZ,
    last_error       TEXT,
    PRIMARY KEY (match_id, provider)
);
