# TIAM Digital — Backend

Spring Boot 3.3 / Java 21 / PostgreSQL backend for the TIAM Digital cognitive-stimulation SaaS.

## Tech Stack

- Java 21, Maven 3.9
- Spring Boot 3.3, Spring Security, Spring Data JPA
- PostgreSQL (local: Docker, production: Supabase)
- Flyway for schema migrations
- MapStruct for DTO mapping
- Lombok for boilerplate reduction
- Springdoc OpenAPI (Swagger UI at `/swagger-ui/index.html`)
- Deployed on Render via Docker

## Package Structure

```
com.tiam/
├── common/
│   ├── audit/       BaseEntity (id, createdAt, updatedAt, activo)
│   ├── web/         ApiResponse<T>, ApiError
│   ├── exception/   ResourceNotFoundException, BadRequestException, GlobalExceptionHandler
│   └── config/      OpenApiConfig, CorsConfig
├── security/        SecurityConfig (+ JwtService, JwtAuthFilter in phase 2)
└── {feature}/       domain/, repository/, service/, web/, dto/, mapper/
```

## Local Development

### Prerequisites
- Java 21, Maven 3.9+, Docker

### 1. Start local Postgres

```bash
docker compose up -d
```

### 2. Configure environment

```bash
cp .env.example .env
# Edit .env if needed (defaults work for local docker)
```

### 3. Run the app

```bash
mvn spring-boot:run
```

Swagger UI: http://localhost:8080/swagger-ui/index.html
Health: http://localhost:8080/actuator/health

## Pointing to Supabase

1. Go to Supabase Dashboard → Project Settings → Database
2. Use the **Connection Pooler** URL (transaction mode, port 6543) for Render compatibility
3. Set these env vars:
   - `DB_URL=jdbc:postgresql://<project>.pooler.supabase.com:6543/postgres`
   - `DB_USERNAME=postgres.<project-ref>`
   - `DB_PASSWORD=<your-password>`
   - `SPRING_PROFILES_ACTIVE=prod`

## Render Deployment

1. Push to GitHub
2. Create a new Web Service on Render, connect the repo
3. Render detects the `Dockerfile` automatically (or use `render.yaml` blueprint)
4. Set the environment variables listed in `.env.example` under the Supabase section
5. Render injects `PORT` automatically — the app reads it via `${PORT:8080}`

## Environment Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `DB_URL` | Yes | JDBC connection URL |
| `DB_USERNAME` | Yes | DB username |
| `DB_PASSWORD` | Yes | DB password |
| `JWT_SECRET` | Yes | 256-bit+ random string for JWT signing |
| `JWT_EXPIRATION_MS` | No | Token TTL in ms (default: 86400000 = 24h) |
| `SPRING_PROFILES_ACTIVE` | No | `dev` (default) or `prod` |
| `CORS_ALLOWED_ORIGINS` | No | Comma-separated allowed origins |
| `PORT` | No (Render injects) | HTTP port (default: 8080) |
