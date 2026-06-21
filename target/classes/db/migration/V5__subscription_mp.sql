-- V5: Add Mercado Pago fields to subscriptions

ALTER TABLE subscriptions
    ADD COLUMN IF NOT EXISTS mp_preapproval_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS plan              VARCHAR(50);

CREATE INDEX IF NOT EXISTS idx_subscriptions_mp_preapproval_id
    ON subscriptions (mp_preapproval_id);
