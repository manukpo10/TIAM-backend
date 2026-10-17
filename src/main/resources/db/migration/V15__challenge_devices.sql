-- Desafío 30 días: which devices open each purchase's link. MEASUREMENT ONLY: nothing reads this table to allow or
-- block access; it answers "do buyers share their link?" before deciding anything.
--
-- device_id is a random id the player keeps in the browser (no personal data, no fingerprinting); device_label is a
-- coarse "OS · browser" taken from the User-Agent ("Android · Chrome"). One row per purchase and device; visits counts
-- how many times that device opened the link. No IP address is stored.
CREATE TABLE challenge_devices (
    id            BIGSERIAL    PRIMARY KEY,
    -- CASCADE: deleting a purchase (a test row, an erasure request) takes its device rows with it.
    purchase_id   BIGINT       NOT NULL REFERENCES challenge_purchases(id) ON DELETE CASCADE,
    device_id     VARCHAR(64)  NOT NULL,
    device_label  VARCHAR(80)  NOT NULL,
    visits        INTEGER      NOT NULL DEFAULT 1,
    first_seen_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_seen_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_challenge_devices_purchase_device UNIQUE (purchase_id, device_id)
);

-- Same two layers as V14 for the pack tables: Supabase exposes every table in "public" to the anon and authenticated
-- roles through its Data API, so row level security goes ON with no policies and their privileges are revoked. The
-- backend connects as the table owner and is not affected.
ALTER TABLE challenge_devices ENABLE ROW LEVEL SECURITY;

-- The roles only exist on Supabase; a plain Postgres (local development, CI) has nothing to revoke.
DO $$
DECLARE
    api_role TEXT;
BEGIN
    FOREACH api_role IN ARRAY ARRAY['anon', 'authenticated'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = api_role) THEN
            EXECUTE format('REVOKE ALL ON TABLE challenge_devices FROM %I', api_role);
            EXECUTE format('REVOKE ALL ON SEQUENCE challenge_devices_id_seq FROM %I', api_role);
        END IF;
    END LOOP;
END
$$;
