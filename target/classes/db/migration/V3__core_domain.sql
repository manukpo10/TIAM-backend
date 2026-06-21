-- =====================
-- Cognitive Areas
-- =====================
CREATE TABLE cognitive_areas (
    id         BIGSERIAL    PRIMARY KEY,
    slug       VARCHAR(100) NOT NULL UNIQUE,
    name       VARCHAR(255) NOT NULL
);

-- Seed 8 fixed cognitive areas
INSERT INTO cognitive_areas (slug, name) VALUES
    ('memoria',                'Memoria'),
    ('atencion',               'Atención'),
    ('fluencia-verbal',        'Fluencia Verbal'),
    ('orientacion-espacial',   'Orientación Espacial'),
    ('funciones-ejecutivas',   'Funciones Ejecutivas'),
    ('praxias',                'Praxias'),
    ('agnosias',               'Agnosias'),
    ('estimulacion-sensorial', 'Estimulación Sensorial');

-- =====================
-- Exercises
-- =====================
CREATE TABLE exercises (
    id                 BIGSERIAL    PRIMARY KEY,
    title              VARCHAR(255) NOT NULL,
    description        TEXT         NOT NULL,
    instructions       TEXT         NOT NULL,
    difficulty         VARCHAR(50)  NOT NULL,
    material_type      VARCHAR(50)  NOT NULL,
    file_url           VARCHAR(500),
    preview_image_url  VARCHAR(500),
    status             VARCHAR(50)  NOT NULL DEFAULT 'PUBLISHED',
    owner_id           BIGINT       REFERENCES users(id),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    activo             BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE INDEX idx_exercises_owner_id ON exercises(owner_id);
CREATE INDEX idx_exercises_status   ON exercises(status);

-- Exercise <-> CognitiveArea join table
CREATE TABLE exercise_cognitive_areas (
    exercise_id       BIGINT NOT NULL REFERENCES exercises(id) ON DELETE CASCADE,
    cognitive_area_id BIGINT NOT NULL REFERENCES cognitive_areas(id),
    PRIMARY KEY (exercise_id, cognitive_area_id)
);

-- =====================
-- Patients
-- =====================
CREATE TABLE patients (
    id               BIGSERIAL    PRIMARY KEY,
    full_name        VARCHAR(255) NOT NULL,
    birth_date       DATE         NOT NULL,
    diagnosis        VARCHAR(500),
    notes            TEXT,
    professional_id  BIGINT       NOT NULL REFERENCES users(id),
    last_session_at  TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    activo           BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE INDEX idx_patients_professional_id ON patients(professional_id);

-- =====================
-- Patient Sessions
-- =====================
CREATE TABLE patient_sessions (
    id               BIGSERIAL    PRIMARY KEY,
    patient_id       BIGINT       NOT NULL REFERENCES patients(id),
    professional_id  BIGINT       NOT NULL REFERENCES users(id),
    title            VARCHAR(255) NOT NULL,
    scheduled_date   TIMESTAMPTZ  NOT NULL,
    notes            TEXT,
    status           VARCHAR(50)  NOT NULL DEFAULT 'COMPLETED',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    activo           BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE INDEX idx_patient_sessions_patient_id ON patient_sessions(patient_id);

-- Session Exercises (child of session)
CREATE TABLE session_exercises (
    id           BIGSERIAL    PRIMARY KEY,
    session_id   BIGINT       NOT NULL REFERENCES patient_sessions(id) ON DELETE CASCADE,
    exercise_id  BIGINT       NOT NULL,
    title        VARCHAR(255) NOT NULL,
    difficulty   VARCHAR(50)  NOT NULL,
    material_type VARCHAR(50) NOT NULL
);

-- Element collection for cognitiveAreaSlugs on SessionExercise
CREATE TABLE session_exercise_area_slugs (
    session_exercise_id  BIGINT       NOT NULL REFERENCES session_exercises(id) ON DELETE CASCADE,
    slug                 VARCHAR(100) NOT NULL
);
