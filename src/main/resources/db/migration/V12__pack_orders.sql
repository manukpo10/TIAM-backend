-- Printable pack sales (Mercado Pago + download links): orders, download audit trail and
-- regret ("botón de arrepentimiento") requests.

CREATE TABLE pack_orders (
    id                    BIGSERIAL     PRIMARY KEY,
    access_token          VARCHAR(64)   NOT NULL,                      -- 32 random bytes, base64url
    status                VARCHAR(20)   NOT NULL DEFAULT 'PENDING',    -- PENDING | PAID | FAILED | REVOKED | REVIEW
    edition               VARCHAR(20)   NOT NULL,                      -- FAMILIAS | PROFESIONALES
    with_games            BOOLEAN       NOT NULL DEFAULT FALSE,
    with_extra            BOOLEAN       NOT NULL DEFAULT FALSE,
    currency              VARCHAR(3)    NOT NULL DEFAULT 'ARS',
    pack_amount           NUMERIC(12,2) NOT NULL,                      -- prices copied at purchase time
    games_amount          NUMERIC(12,2) NOT NULL DEFAULT 0,
    extra_amount          NUMERIC(12,2) NOT NULL DEFAULT 0,
    total_amount          NUMERIC(12,2) NOT NULL,
    buyer_name            VARCHAR(255)  NOT NULL,
    email                 VARCHAR(255)  NOT NULL,                      -- stored lower-cased
    phone                 VARCHAR(50),                                 -- normalized; required only with the games add-on
    catalog_version       VARCHAR(20)   NOT NULL DEFAULT 'v1',         -- storage key version: old orders keep their files
    mp_preference_id      VARCHAR(255),
    init_point            TEXT,                                        -- lets a double click reuse the same checkout
    mp_payment_id         VARCHAR(255),
    paid_at               TIMESTAMPTZ,
    access_expires_at     TIMESTAMPTZ,
    email_sent_at         TIMESTAMPTZ,
    challenge_purchase_id BIGINT REFERENCES challenge_purchases(id),   -- the bundled Desafío month, when bought
    utm_source            VARCHAR(100),
    utm_medium            VARCHAR(100),
    utm_campaign          VARCHAR(100),
    utm_content           VARCHAR(100),
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    activo                BOOLEAN       NOT NULL DEFAULT TRUE
);
CREATE UNIQUE INDEX idx_pack_orders_access_token ON pack_orders(access_token);
-- One Mercado Pago payment can never credit two orders.
CREATE UNIQUE INDEX idx_pack_orders_mp_payment_id ON pack_orders(mp_payment_id) WHERE mp_payment_id IS NOT NULL;
CREATE INDEX idx_pack_orders_status_created ON pack_orders(status, created_at);
CREATE INDEX idx_pack_orders_email ON pack_orders(email);

-- Audit trail and anti-sharing caps: one row per signed download URL issued.
CREATE TABLE pack_download_events (
    id         BIGSERIAL    PRIMARY KEY,
    order_id   BIGINT       NOT NULL REFERENCES pack_orders(id),
    file_key   VARCHAR(100) NOT NULL,                                  -- catalog file code, never the storage key
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_pack_download_events_order ON pack_download_events(order_id, created_at);

CREATE TABLE pack_regret_requests (
    id         BIGSERIAL    PRIMARY KEY,
    order_id   BIGINT       REFERENCES pack_orders(id),                -- linked only when the order belongs to the same email
    full_name  VARCHAR(255) NOT NULL,
    email      VARCHAR(255) NOT NULL,
    order_ref  VARCHAR(100),
    reason     TEXT,
    status     VARCHAR(20)  NOT NULL DEFAULT 'RECEIVED',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_pack_regret_requests_email ON pack_regret_requests(email);
