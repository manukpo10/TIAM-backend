-- V2: Auth, User, and Subscription tables

-- Users
CREATE TABLE users (
    id             BIGSERIAL PRIMARY KEY,
    email          VARCHAR(255) NOT NULL UNIQUE,
    password_hash  VARCHAR(255) NOT NULL,
    full_name      VARCHAR(255) NOT NULL,
    specialty      VARCHAR(255),
    role           VARCHAR(50)  NOT NULL DEFAULT 'PROFESSIONAL',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    activo         BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE INDEX idx_users_email ON users (email);

-- Subscriptions
CREATE TABLE subscriptions (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT       NOT NULL UNIQUE REFERENCES users (id),
    status              VARCHAR(50)  NOT NULL,
    trial_ends_at       TIMESTAMPTZ,
    current_period_end  TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    activo              BOOLEAN      NOT NULL DEFAULT TRUE
);

-- Password Reset Tokens
CREATE TABLE password_reset_tokens (
    id          BIGSERIAL PRIMARY KEY,
    token       VARCHAR(255) NOT NULL UNIQUE,
    user_id     BIGINT       NOT NULL REFERENCES users (id),
    expires_at  TIMESTAMPTZ  NOT NULL,
    used        BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    activo      BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE INDEX idx_password_reset_tokens_token ON password_reset_tokens (token);
