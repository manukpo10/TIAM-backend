-- The pack tables hold buyers' emails, names, phones and download tokens (a token is a credential that is worth
-- money). On Supabase every table in "public" is exposed by the Data API (PostgREST) to the "anon" and
-- "authenticated" roles, and the anon key is public by design, so without row level security anyone holding it
-- could read these tables. Two layers, so a mistake in one does not expose the data:
--   1. Row level security ON with NO policies: those roles see zero rows and can write nothing.
--   2. Their table and sequence privileges are revoked.
--
-- The backend is NOT affected: it connects with the database owner role (the one that runs these migrations), and a
-- table owner is exempt from RLS unless the table is FORCEd, which is deliberately not done here.
--
-- Only the tables created in V12 are touched. challenge_purchases (and the older tables) have the same exposure and
-- are left as they are on purpose: see the recommendation in pack/cobro-revision.md.
ALTER TABLE pack_orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE pack_download_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE pack_regret_requests ENABLE ROW LEVEL SECURITY;

-- The roles only exist on Supabase; a plain Postgres (local development, CI) has nothing to revoke.
DO $$
DECLARE
    api_role TEXT;
BEGIN
    FOREACH api_role IN ARRAY ARRAY['anon', 'authenticated'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = api_role) THEN
            EXECUTE format('REVOKE ALL ON TABLE pack_orders, pack_download_events, pack_regret_requests FROM %I', api_role);
            EXECUTE format('REVOKE ALL ON SEQUENCE pack_orders_id_seq, pack_download_events_id_seq, pack_regret_requests_id_seq FROM %I', api_role);
        END IF;
    END LOOP;
END
$$;
