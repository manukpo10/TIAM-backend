-- Leads captured from the public "recursos gratuitos" lead magnet.
CREATE TABLE leads (
    id          BIGSERIAL    PRIMARY KEY,
    email       VARCHAR(255) NOT NULL,
    name        VARCHAR(255),
    source      VARCHAR(100) NOT NULL DEFAULT 'recursos',
    consent     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    activo      BOOLEAN      NOT NULL DEFAULT TRUE
);
CREATE INDEX IF NOT EXISTS idx_leads_email ON leads(email);
CREATE INDEX IF NOT EXISTS idx_leads_created_at ON leads(created_at);
