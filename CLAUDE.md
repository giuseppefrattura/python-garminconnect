# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Architecture

This project is a **microservice stack** that exposes Garmin Connect and Renpho smart-scale fitness data via REST APIs, with a Spring Boot dashboard as the single entry point. It replaces the old monolithic `api_server.py` (deleted).

```
docker-compose.yml
├── garmin-proxy    (Python 3.13 / FastAPI, port 8080)
│     └── main.py          thin HTTP proxy to Garmin Connect API
├── garmin-service  (Java 21 / Spring Boot, port 8081)
│     ├── controller/      REST endpoints + static PWA dashboard
│     ├── service/         business logic (HR zones, strength, biometrics, PRs)
│     ├── client/          GarminProxyClient (RestClient + @Retryable)
│     ├── security/        Spring Security: form login + OAuth2 (Google/Apple),
│     │                    ApiKeyFilter, RateLimitingFilter, TOTP 2FA
│     ├── scheduler/       GarminSyncScheduler (daily 3 AM sync cron)
│     ├── model/           JPA entities
│     ├── repository/      Spring Data JPA
│     └── dto/             request/response POJOs
└── renpho-service  (Python 3.12 / FastAPI, port 8082)
      └── main.py           Renpho Health sync → PostgreSQL (own schema mgmt)
```

**Data flow:**
```
Client → garmin-service:8081 → garmin-proxy:8080 → Garmin Connect
                             → renpho-service:8082 → Renpho Health
                             ↘ PostgreSQL (external host, not in compose)
```

**Key design decisions:**
- `garmin-proxy` contains **zero business logic** — raw Garmin API forwarding only, with a 1-hour in-memory cache
- `garmin-proxy` auto-re-authenticates on `GarthHTTPError` (expired token) via `_call_with_reauth()`
- `garmin-proxy` auth is via `X-API-Key` header (bypassed if `GARMIN_API_KEY` is unset)
- `garmin-service` retries proxy calls 3× with exponential backoff via `@Retryable`
- DB migrations for Garmin data are managed by **Liquibase** (Hibernate is `ddl-auto: validate`); `renpho-service` manages its own table with `CREATE TABLE IF NOT EXISTS` at startup
- `POST /api/run-hr-zones/persist` writes to DB; `GET /api/run-hr-zones` is read-only
- `RenphoProxyController` is a catch-all `/**` proxy forwarding to renpho-service
- The dashboard frontend is a static PWA in `garmin-service/src/main/resources/static/` (index.html, sw.js, manifest.webmanifest)
- Production deploys via `docker-compose.prod.yml` using GHCR images + Watchtower auto-update

## Common Commands

### Run the full stack
```bash
docker compose up --build
```

### Run only one service
```bash
docker compose up --build garmin-proxy
docker compose up --build garmin-service
docker compose up --build renpho-service
```

### garmin-service — Java tests
```bash
# Requires Docker (Maven not installed locally)
docker run --rm \
  -v $(pwd)/garmin-service:/build \
  -w /build \
  maven:3.9-eclipse-temurin-21 \
  mvn test -B

# Run a single test class
docker run --rm \
  -v $(pwd)/garmin-service:/build \
  -w /build \
  maven:3.9-eclipse-temurin-21 \
  mvn test -B -Dtest=RunHrZoneServiceTest

# Run a single test method
docker run --rm \
  -v $(pwd)/garmin-service:/build \
  -w /build \
  maven:3.9-eclipse-temurin-21 \
  mvn test -B -Dtest="RunHrZoneServiceTest#createsNewEntity"
```

### garmin-proxy — Python tests
```bash
# Requires Docker (avoids PEP 668 pip restrictions on macOS)
docker run --rm \
  -v $(pwd)/garmin-proxy:/app \
  -w /app \
  python:3.12-slim \
  bash -c "pip install -q garminconnect garth fastapi httpx pytest python-dotenv uvicorn && \
           pytest tests/ -v"
```

### garmin-proxy — linting
```bash
cd garmin-proxy
ruff check main.py
```

### garminconnect library — Python tests
```bash
cd /path/to/repo
python -m pytest tests/test_garmin.py -v
```

## Key Endpoints

### garmin-service (port 8081)
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/last-strength-workout?limit=N` | Latest strength workout + volume |
| GET | `/api/strength-workouts-history` | Strength workout history |
| GET | `/api/exercise-progression` | Per-exercise load progression |
| POST | `/api/sync/strength-workouts` | Fetch + persist strength workouts |
| POST | `/api/exercise-set/name` | Rename/normalize an exercise |
| GET | `/api/run-hr-zones?days=N` | Aggregated HR zone minutes (read-only) |
| GET | `/api/run-hr-zones/db` | HR zones from DB only |
| POST | `/api/run-hr-zones/persist?days=N` | Fetch + upsert HR zones to PostgreSQL |
| GET | `/api/personal-records` | Exercise PRs (1RM estimates) |
| POST | `/api/personal-records/recalculate` | Recompute PRs |
| GET | `/api/health/today-readiness` | Daily readiness score |
| POST | `/api/health/sync` | Sync daily health metrics from Garmin |
| GET | `/api/biometrics/relative-strength` | Strength-to-bodyweight analytics |
| GET | `/api/biometrics/recomposition-trend` | Weight vs muscle/fat trend (Renpho) |
| GET | `/api/auth/status` | Current user + 2FA state |
| POST | `/api/auth/2fa/setup|enable|disable` | TOTP 2FA management |
| GET | `/api/renpho/**` | Proxied to renpho-service |
| GET | `/actuator/health` | Health check |

### garmin-proxy (port 8080)
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/activities` | Raw Garmin activities list |
| GET | `/api/activities/by-date` | Activities filtered by date/type |
| GET | `/api/activities/{id}/hr-zones` | HR zone breakdown for activity |
| GET | `/api/activities/{id}/exercise-sets` | Strength sets for activity |
| GET | `/health` | Health + token validity check |
| GET | `/docs` | Swagger UI |

### renpho-service (port 8082)
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/renpho/measurements` | All persisted weigh-ins from DB |
| POST | `/api/renpho/sync` | Trigger manual sync (also runs daily in background) |

## Configuration

Environment variables are wired through `.env` → `docker-compose.yml`.

### garmin-service
| Variable | Default | Description |
|----------|---------|-------------|
| `GARMIN_PROXY_URL` | `http://localhost:8080` | garmin-proxy base URL |
| `GARMIN_API_KEY` | — | Shared secret for proxy & renpho calls |
| `RENPHO_SERVICE_URL` | `http://renpho-service:8082` | renpho-service base URL |
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | `localhost` / `5432` / `garmin` / `postgres` / `postgres` | PostgreSQL |
| `DASHBOARD_USER` / `DASHBOARD_PASSWORD` | `admin` / `admin` | Form login credentials |
| `GOOGLE_CLIENT_ID`/`SECRET`, `APPLE_CLIENT_ID`/`SECRET` | placeholders | OAuth2 login |
| `HR_ZONES_DEFAULT_DAYS` | `10` | Default lookback for HR zone queries |
| `WORKOUTS_SEARCH_LIMIT` | `30` | Default activity search window for strength |

### garmin-proxy
| Variable | Default | Description |
|----------|---------|-------------|
| `GARMINTOKENS` | `~/.garminconnect` | Path to Garth token store (volume mount) |
| `GARMIN_API_KEY` | — | Required `X-API-Key` (auth bypassed if unset) |

### renpho-service
| Variable | Description |
|----------|-------------|
| `RENPHO_EMAIL` / `RENPHO_PASSWORD` | Renpho Health credentials |
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | PostgreSQL (same DB as garmin-service) |

## Database Migrations (Liquibase)

Migrations live in `garmin-service/src/main/resources/db/changelog/migrations/`.
To add a new migration, create `00N-description.yaml` and include it in `db.changelog-master.yaml`.

Migration `001` has a `preConditions: onFail: MARK_RAN` guard — safe to run on an existing DB.
Note: the `renpho_measurements` table is NOT managed by Liquibase (created by renpho-service itself).

## Test Structure

```
garmin-service/src/test/java/.../service/
├── BiometricsAnalyticsServiceTest.java
├── GarminHealthSyncServiceTest.java
├── PersonalRecordServiceTest.java
├── ReadinessCalculationServiceTest.java
├── RunHrZoneServiceTest.java
└── StrengthWorkoutServiceTest.java

garmin-proxy/tests/
└── test_main.py   (authentication, re-auth, endpoint integration)

tests/             (garminconnect library tests, VCR cassettes)
├── conftest.py
└── test_garmin.py
```

All `garmin-service` tests use Mockito — no database or running proxy required.
