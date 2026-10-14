-- Desafío 30 días: records when the "tu Desafío está listo" email was accepted by
-- the email provider. NULL means it was not sent: the buyer left no email, the
-- provider is not configured (RESEND_API_KEY unset), or the delivery failed.
ALTER TABLE challenge_purchases ADD COLUMN email_sent_at TIMESTAMPTZ;

-- No email for purchases paid before this migration: those buyers already got their
-- access some other way (WhatsApp). The retry job mails every PAID purchase of the last
-- 72 hours whose email_sent_at is NULL, so without this the day the email provider is
-- configured it would send "tu Desafío está listo" to customers who already activated.
-- Marking them "handled" makes that true. PENDING/FAILED stay NULL: a pending purchase
-- that is paid later must still get its email.
UPDATE challenge_purchases SET email_sent_at = NOW() WHERE status = 'PAID';
