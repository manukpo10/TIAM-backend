-- V1: Baseline migration
-- Domain tables will be added in subsequent migrations.
-- Enabling useful Postgres extensions.

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Placeholder comment: domain entity tables (e.g. usuarios, actividades, sesiones)
-- will be created in V2 and onwards.
