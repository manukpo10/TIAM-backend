-- Add play_token to patients
ALTER TABLE patients ADD COLUMN IF NOT EXISTS play_token VARCHAR(255);
UPDATE patients SET play_token = gen_random_uuid()::text WHERE play_token IS NULL;
ALTER TABLE patients ALTER COLUMN play_token SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS idx_patients_play_token ON patients(play_token);

-- Home subscriptions
CREATE TABLE home_subscriptions (
    id                   BIGSERIAL    PRIMARY KEY,
    patient_id           BIGINT       NOT NULL REFERENCES patients(id),
    status               VARCHAR(50)  NOT NULL DEFAULT 'INACTIVE',
    current_period_end   TIMESTAMPTZ,
    mp_preapproval_id    VARCHAR(255),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    activo               BOOLEAN      NOT NULL DEFAULT TRUE
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_home_subscriptions_patient_id ON home_subscriptions(patient_id);

-- Home exercise results
CREATE TABLE home_exercise_results (
    id               BIGSERIAL    PRIMARY KEY,
    patient_id       BIGINT       NOT NULL REFERENCES patients(id),
    exercise_type    VARCHAR(50)  NOT NULL,
    exercise_title   VARCHAR(255) NOT NULL,
    completed_at     TIMESTAMPTZ  NOT NULL,
    completed        BOOLEAN      NOT NULL DEFAULT TRUE,
    moves            INTEGER      NOT NULL DEFAULT 0,
    duration_seconds INTEGER      NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    activo           BOOLEAN      NOT NULL DEFAULT TRUE
);
CREATE INDEX IF NOT EXISTS idx_home_exercise_results_patient_id ON home_exercise_results(patient_id);
