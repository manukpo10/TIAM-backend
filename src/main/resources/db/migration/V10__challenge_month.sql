-- Desafío 30 días: Mes 2 support. Each purchase is now scoped to a specific
-- month's 30-day catalog (1 = original, 2 = new independent catalog sold as
-- its own one-time purchase at the same price). Existing rows predate months
-- entirely, so they backfill to 1 via the column default.
ALTER TABLE challenge_purchases ADD COLUMN challenge_month INTEGER NOT NULL DEFAULT 1;
