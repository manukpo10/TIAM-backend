-- Desafío 30 días: per-day results (stars, mistakes) feeding the progress panel
CREATE TABLE challenge_day_results (
    id                     BIGSERIAL    PRIMARY KEY,
    challenge_purchase_id  BIGINT       NOT NULL REFERENCES challenge_purchases(id),
    day                    INTEGER      NOT NULL,
    area                   VARCHAR(50)  NOT NULL,
    mistakes               INTEGER      NOT NULL,
    total_attempts         INTEGER      NOT NULL,
    stars                  INTEGER      NOT NULL,
    played_at              TIMESTAMPTZ  NOT NULL,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    activo                 BOOLEAN      NOT NULL DEFAULT TRUE
);
-- Composite unique index doubles as the upsert key (one result per purchase+day)
-- and already covers lookups filtered by challenge_purchase_id alone (leftmost
-- column), so no separate single-column index is needed.
CREATE UNIQUE INDEX IF NOT EXISTS idx_challenge_day_results_purchase_day ON challenge_day_results(challenge_purchase_id, day);
