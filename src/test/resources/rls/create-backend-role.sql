-- Recreates, in a plain Postgres, the situation of a Supabase project:
--  * "anon" and "authenticated" are the roles the public Data API (PostgREST) uses with the anon / user keys;
--  * the backend connects as an ordinary login role that is NOT a superuser and does NOT bypass row level security,
--    and it owns the tables because its own Flyway migrations create them;
--  * Supabase grants every new table in "public" to anon / authenticated by default (default privileges).
CREATE ROLE anon NOLOGIN;
CREATE ROLE authenticated NOLOGIN;
CREATE ROLE tiam_backend LOGIN PASSWORD 'tiam_backend_test' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;

GRANT ALL ON SCHEMA public TO tiam_backend;
GRANT USAGE ON SCHEMA public TO anon, authenticated;

ALTER DEFAULT PRIVILEGES FOR ROLE tiam_backend IN SCHEMA public GRANT ALL ON TABLES TO anon, authenticated;
ALTER DEFAULT PRIVILEGES FOR ROLE tiam_backend IN SCHEMA public GRANT ALL ON SEQUENCES TO anon, authenticated;

-- V1 installs pgcrypto, a "trusted" extension: any role with CREATE on the database may install it.
GRANT CREATE ON DATABASE test TO tiam_backend;
