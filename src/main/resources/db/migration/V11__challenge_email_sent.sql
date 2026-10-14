-- Desafío 30 días: records when the "tu Desafío está listo" email was accepted by
-- the email provider. NULL means it was not sent: the buyer left no email, the
-- provider is not configured (RESEND_API_KEY unset), or the delivery failed.
-- No backfill on purpose: purchases paid before this migration never get the email.
ALTER TABLE challenge_purchases ADD COLUMN email_sent_at TIMESTAMPTZ;
