-- Desafío 30 días: one-time-payment digital product purchases
CREATE TABLE challenge_purchases (
    id               BIGSERIAL    PRIMARY KEY,
    buyer_name       VARCHAR(255) NOT NULL,
    phone            VARCHAR(50)  NOT NULL,
    email            VARCHAR(255),
    status           VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
    access_token     VARCHAR(255) NOT NULL,
    purchase_date    TIMESTAMPTZ,
    mp_payment_id    VARCHAR(255),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    activo           BOOLEAN      NOT NULL DEFAULT TRUE
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_challenge_purchases_access_token ON challenge_purchases(access_token);
CREATE INDEX IF NOT EXISTS idx_challenge_purchases_mp_payment_id ON challenge_purchases(mp_payment_id);
