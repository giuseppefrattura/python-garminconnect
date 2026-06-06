# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Architecture

This project is a **microservice stack** that exposes Garmin Connect fitness data via REST APIs. It replaces the old monolithic `api_server.py` (deleted).

```
docker-compose.yml
├── garmin-proxy   (Python / FastAPI, port 8080)
│     └── main.py        thin HTTP proxy to Garmin Connect API
└── garmin-service (Java 21 / Spring Boot, port 8081)
      ├── controller/    REST endpoints
      ├── service/       business logic (HR zones, strength workouts)
      ├── client/        GarminProxyClient (RestClient + @Retryable)
      ├── model/         JPA entity: RunningHrZone
      ├── repository/    Spring Data JPA
      └── dto/           request/response POJOs
```

**Data flow:**
```
Client → garmin-service:8081 → garmin-proxy:8080 → Garmin Connect
                             ↘ PostgreSQL (external host, not in compose)
```

**Key design decisions:**
- `garmin-proxy` contains **zero business logic** — raw Garmin API forwarding only
- `garmin-proxy` auto-re-authenticates on `GarthHTTPError` (expired token) via `_call_with_reauth()`
- `garmin-service` retries proxy calls 3× with exponential backoff via `@Retryable`
- DB migrations are managed by **Liquibase** (not Hibernate ddl-auto)
- `POST /api/run-hr-zones/persist` writes to DB; `GET /api/run-hr-zones` is read-only

## Common Commands

### Run the full stack
```bash
docker compose up --build
```

### Run only one service
```bash
docker compose up --build garmin-proxy
docker compose up --build garmin-service
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

| Method | Path | Service | Description |
|--------|------|---------|-------------|
| GET | `/api/activities` | proxy | Raw Garmin activities list |
| GET | `/api/activities/by-date` | proxy | Activities filtered by date/type |
| GET | `/api/activities/{id}/hr-zones` | proxy | HR zone breakdown for activity |
| GET | `/api/activities/{id}/exercise-sets` | proxy | Strength sets for activity |
| GET | `/api/last-strength-workout?limit=N` | service | Latest strength workout + volume |
| GET | `/api/run-hr-zones?days=N` | service | Aggregated HR zone minutes (read-only) |
| POST | `/api/run-hr-zones/persist?days=N` | service | Fetch + upsert HR zones to PostgreSQL |
| GET | `/docs` | both | Swagger UI (FastAPI auto, Springdoc) |
| GET | `/actuator/health` | service | Health check |

## Configuration

### garmin-service environment variables
| Variable | Default | Description |
|----------|---------|-------------|
| `GARMIN_PROXY_URL` | `http://localhost:8080` | garmin-proxy base URL |
| `DB_HOST` | `localhost` | PostgreSQL host (external) |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `garmin` | PostgreSQL database name |
| `DB_USER` | `postgres` | PostgreSQL user |
| `DB_PASSWORD` | `postgres` | PostgreSQL password |
| `HR_ZONES_DEFAULT_DAYS` | `10` | Default lookback for HR zone queries |
| `WORKOUTS_SEARCH_LIMIT` | `30` | Default activity search window for strength |

### garmin-proxy environment variables
| Variable | Default | Description |
|----------|---------|-------------|
| `GARMINTOKENS` | `~/.garminconnect` | Path to Garth token store (volume mount) |

## Database Migrations (Liquibase)

Migrations live in `garmin-service/src/main/resources/db/changelog/migrations/`.
To add a new migration, create `00N-description.yaml` and include it in `db.changelog-master.yaml`.

Migration `001` has a `preConditions: onFail: MARK_RAN` guard — safe to run on an existing DB.

## Test Structure

```
garmin-service/src/test/java/.../service/
├── StrengthWorkoutServiceTest.java   (13 tests)
└── RunHrZoneServiceTest.java         (12 tests)

garmin-proxy/tests/
└── test_main.py   (authentication, re-auth, endpoint integration)

tests/                                (garminconnect library tests)
├── conftest.py
└── test_garmin.py
```

All `garmin-service` tests use Mockito — no database or running proxy required.