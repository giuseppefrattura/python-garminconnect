#!/usr/bin/env python3
"""
REST API server exposing Garmin Connect data endpoints.

Swagger UI:  GET /docs
ReDoc:        GET /redoc
OpenAPI JSON: GET /openapi.json

Endpoints:
  GET /api/last_strength_workout  - Latest strength workout details
  GET /api/run_hr_zones           - Aggregated HR zone minutes (last 10 days)
  GET /api/run_hr_zones_postgres  - HR zones persisted to PostgreSQL
"""

import datetime
import logging
import os
from contextlib import asynccontextmanager
from datetime import timedelta

import time

import psycopg2
from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import RedirectResponse
from prometheus_fastapi_instrumentator import Instrumentator

from garminconnect import (
    Garmin,
    GarminConnectAuthenticationError,
    GarminConnectConnectionError,
)
from garth.exc import GarthHTTPError

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
load_dotenv()

logging.basicConfig(level=logging.INFO)
log = logging.getLogger(__name__)
logging.getLogger("garminconnect").setLevel(logging.CRITICAL)

# Global Garmin API instance (authenticated once)
_garmin_api: Garmin | None = None


# ---------------------------------------------------------------------------
# Lifespan (startup / shutdown)
# ---------------------------------------------------------------------------
@asynccontextmanager
async def lifespan(application: FastAPI):
    """Authenticate with Garmin on startup."""
    log.info("Garmin API server starting up…")
    try:
        get_garmin_api()
    except RuntimeError:
        log.warning("Garmin auth failed at startup – will retry on first request.")
    yield
    log.info("Garmin API server shutting down.")


app = FastAPI(
    title="Garmin Connect API",
    description="REST API exposing Garmin Connect fitness data.",
    version="1.0.0",
    lifespan=lifespan,
)

Instrumentator().instrument(app).expose(app)

@app.middleware("http")
async def add_process_time_header(request: Request, call_next):
    start_time = time.time()
    response = await call_next(request)
    process_time = time.time() - start_time
    log.info("%s %s - %s - %.4fs", request.method, request.url.path, response.status_code, process_time)
    response.headers["X-Process-Time"] = str(process_time)
    return response


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
def get_garmin_api() -> Garmin:
    """Return the shared Garmin API instance, authenticating on first call."""
    global _garmin_api
    if _garmin_api is not None:
        return _garmin_api

    tokenstore = os.getenv("GARMINTOKENS") or "~/.garminconnect"
    log.info("Authenticating with Garmin using tokens from: %s", tokenstore)

    try:
        api = Garmin()
        api.login(tokenstore)
        log.info("Garmin authentication successful.")
        _garmin_api = api
        return api
    except (
        FileNotFoundError,
        GarthHTTPError,
        GarminConnectAuthenticationError,
        GarminConnectConnectionError,
    ) as exc:
        log.error("Garmin authentication failed: %s", exc)
        raise RuntimeError(
            "Garmin authentication failed. Run demo.py first to store tokens."
        ) from exc


def format_duration(seconds: float) -> str:
    """Format duration in seconds to HH:MM:SS."""
    if not seconds:
        return "00:00:00"
    return str(datetime.timedelta(seconds=int(seconds)))


def _connect_db():
    """Open a connection to the PostgreSQL database."""
    return psycopg2.connect(
        host=os.getenv("DB_HOST", "localhost"),
        database=os.getenv("DB_NAME", "garmin"),
        user=os.getenv("DB_USER", "postgres"),
        password=os.getenv("DB_PASSWORD", "postgres"),
        port=os.getenv("DB_PORT", "5432"),
    )


def _ensure_table(conn):
    """Create the running_hr_zones table if not present."""
    with conn.cursor() as cur:
        cur.execute("""
            CREATE TABLE IF NOT EXISTS running_hr_zones (
                id SERIAL PRIMARY KEY,
                activity_id BIGINT UNIQUE NOT NULL,
                activity_name VARCHAR(255),
                run_date DATE NOT NULL,
                run_time TIME NOT NULL,
                zone_1_mins NUMERIC(5,2) DEFAULT 0,
                zone_2_mins NUMERIC(5,2) DEFAULT 0,
                zone_3_mins NUMERIC(5,2) DEFAULT 0,
                zone_4_mins NUMERIC(5,2) DEFAULT 0,
                zone_5_mins NUMERIC(5,2) DEFAULT 0,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            );
        """)
    conn.commit()


# Mapping from Garmin exercise categories to muscle groups
MUSCLE_GROUP_MAP = {
    "BENCH_PRESS": "Chest",
    "CHEST_FLY": "Chest",
    "PUSH_UP": "Chest",
    "PULL_UP": "Back",
    "ROW": "Back",
    "LAT_PULLDOWN": "Back",
    "DEADLIFT": "Back/Legs (Posterior Chain)",
    "SQUAT": "Legs",
    "LUNGE": "Legs",
    "LEG_PRESS": "Legs",
    "LEG_CURL": "Legs",
    "LEG_EXTENSION": "Legs",
    "CALF_RAISE": "Calves",
    "SHOULDER_PRESS": "Shoulders",
    "LATERAL_RAISE": "Shoulders",
    "FRONT_RAISE": "Shoulders",
    "BICEP_CURL": "Biceps",
    "TRICEPS_EXTENSION": "Triceps",
    "CRUNCH": "Core",
    "SIT_UP": "Core",
    "PLANK": "Core",
    "CORE": "Core",
    "OLYMPIC_LIFT": "Full Body (Olympic)",
    "CARRY": "Full Body/Core",
    "FARMERS_WALK": "Full Body/Core",
}


# ---------------------------------------------------------------------------
# Root redirect → Swagger UI
# ---------------------------------------------------------------------------
@app.get("/", include_in_schema=False)
async def root():
    """Redirect to the Swagger UI documentation."""
    return RedirectResponse(url="/docs")


# ---------------------------------------------------------------------------
# Endpoint: /health
# ---------------------------------------------------------------------------
@app.get(
    "/health",
    summary="Health check",
    tags=["System"],
)
async def health_check():
    """Return health status of the API."""
    return {"status": "ok", "garmin_api_initialized": _garmin_api is not None}


# ---------------------------------------------------------------------------
# Endpoint: /api/last_strength_workout
# ---------------------------------------------------------------------------
@app.get(
    "/api/last_strength_workout",
    summary="Latest strength workout",
    description="Returns the most recent strength/weight training activity with sets, reps, weights, and volume breakdown by muscle group.",
    tags=["Workouts"],
)
async def last_strength_workout():
    """Return the most recent strength/weight training activity."""
    try:
        api = get_garmin_api()
        activities = api.get_activities(0, 30)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc))

    # Find latest strength activity
    strength_activity = None
    for activity in activities:
        activity_type = activity.get("activityType", {}).get("typeKey", "")
        if "strength" in activity_type.lower() or "training" in activity_type.lower():
            strength_activity = activity
            break

    if not strength_activity:
        raise HTTPException(
            status_code=404,
            detail="No strength training activity found in the last 30 activities.",
        )

    activity_id = strength_activity.get("activityId")

    # General stats
    result = {
        "activityId": activity_id,
        "activityName": strength_activity.get("activityName", "Unknown"),
        "startTimeLocal": strength_activity.get("startTimeLocal", "Unknown"),
        "duration": format_duration(strength_activity.get("duration", 0)),
        "durationSeconds": strength_activity.get("duration", 0),
        "calories": strength_activity.get("calories", 0),
        "averageHR": strength_activity.get("averageHR"),
        "maxHR": strength_activity.get("maxHR"),
        "aerobicTrainingEffect": strength_activity.get("aerobicTrainingEffect"),
        "anaerobicTrainingEffect": strength_activity.get("anaerobicTrainingEffect"),
        "sets": [],
        "volumeByMuscleGroup": {},
    }

    # Fetch exercise sets
    try:
        sets_data = api.get_activity_exercise_sets(activity_id)
        if sets_data and isinstance(sets_data, dict) and "exerciseSets" in sets_data:
            volume_by_group: dict[str, float] = {}
            set_num = 1

            for ex_set in sets_data["exerciseSets"]:
                if ex_set.get("setType") == "REST":
                    continue

                exercises = ex_set.get("exercises", [])
                ex_name = "Unknown Exercise"
                category = None
                if exercises and isinstance(exercises, list) and len(exercises) > 0:
                    first_ex = exercises[0]
                    category = first_ex.get("category", "")
                    name = first_ex.get("name")
                    if name:
                        ex_name = name.replace("_", " ").title()
                    elif category:
                        ex_name = category.replace("_", " ").title()

                reps = ex_set.get("repetitionCount") or 0
                raw_weight = ex_set.get("weight", 0.0)
                weight_kg = raw_weight / 1000.0 if raw_weight and raw_weight > 0 else 0.0

                if reps > 0 or weight_kg > 0 or ex_name != "Unknown Exercise":
                    result["sets"].append({
                        "setNumber": set_num,
                        "exercise": ex_name,
                        "reps": reps,
                        "weightKg": round(weight_kg, 1),
                    })
                    set_num += 1

                    # Calculate volume
                    if weight_kg > 0 and reps > 0:
                        muscular_group = "Unknown"
                        if category:
                            muscular_group = MUSCLE_GROUP_MAP.get(
                                category, category.replace("_", " ").title()
                            )
                        else:
                            muscular_group = ex_name
                        volume_by_group[muscular_group] = (
                            volume_by_group.get(muscular_group, 0.0) + reps * weight_kg
                        )

            result["volumeByMuscleGroup"] = {
                k: round(v, 1)
                for k, v in sorted(
                    volume_by_group.items(), key=lambda x: x[1], reverse=True
                )
            }
    except Exception as exc:
        log.warning("Could not fetch exercise sets: %s", exc)

    return {"status": "success", "data": result}


# ---------------------------------------------------------------------------
# Endpoint: /api/run_hr_zones
# ---------------------------------------------------------------------------
@app.get(
    "/api/run_hr_zones",
    summary="Running HR zones (last 10 days)",
    description="Returns aggregated heart rate zone minutes for all running activities in the last 10 days, with per-activity breakdown.",
    tags=["Running"],
)
async def run_hr_zones():
    """Return aggregated HR zone minutes for runs in the last 10 days."""
    try:
        api = get_garmin_api()
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc))

    today = datetime.date.today()
    start_date = today - timedelta(days=10)

    try:
        activities = api.get_activities_by_date(
            start_date.isoformat(), today.isoformat(), "running"
        )
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc))

    if not activities:
        return {
            "status": "success",
            "data": {
                "period": {"from": start_date.isoformat(), "to": today.isoformat()},
                "activitiesCount": 0,
                "zones": {},
                "activities": [],
            },
        }

    aggregated_zones: dict[int, float] = {}
    activity_list = []

    for activity in activities:
        activity_id = activity.get("activityId")
        activity_name = activity.get("activityName", "Unknown")
        start_time = activity.get("startTimeLocal", "Unknown Date")

        entry = {"activityId": activity_id, "activityName": activity_name, "startTimeLocal": start_time}

        try:
            hr_zones_data = api.get_activity_hr_in_timezones(activity_id)
            if hr_zones_data and isinstance(hr_zones_data, list):
                zones_for_activity = {}
                for zone_info in hr_zones_data:
                    zone_number = zone_info.get("zoneNumber")
                    secs_in_zone = zone_info.get("secsInZone", 0)
                    if zone_number is not None:
                        aggregated_zones[zone_number] = (
                            aggregated_zones.get(zone_number, 0) + secs_in_zone
                        )
                        zones_for_activity[f"zone_{zone_number}"] = round(
                            secs_in_zone / 60.0, 1
                        )
                entry["zones"] = zones_for_activity
        except Exception as exc:
            entry["error"] = str(exc)

        activity_list.append(entry)

    # Convert aggregated seconds to minutes
    zones_minutes = {
        f"zone_{z}": round(s / 60.0, 1)
        for z, s in sorted(aggregated_zones.items())
    }

    return {
        "status": "success",
        "data": {
            "period": {"from": start_date.isoformat(), "to": today.isoformat()},
            "activitiesCount": len(activities),
            "zones": zones_minutes,
            "activities": activity_list,
        },
    }


# ---------------------------------------------------------------------------
# Endpoint: /api/run_hr_zones_postgres
# ---------------------------------------------------------------------------
@app.get(
    "/api/run_hr_zones_postgres",
    summary="Running HR zones → PostgreSQL",
    description="Fetches HR zones for runs in the last 10 days, persists them to PostgreSQL (upsert), and returns the saved records.",
    tags=["Running"],
)
async def run_hr_zones_postgres():
    """Fetch HR zones for runs, persist to PostgreSQL, return saved records."""
    try:
        api = get_garmin_api()
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc))

    # Connect to database
    try:
        db_conn = _connect_db()
        _ensure_table(db_conn)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"Database error: {exc}")

    today = datetime.date.today()
    start_date = today - timedelta(days=10)

    try:
        activities = api.get_activities_by_date(
            start_date.isoformat(), today.isoformat(), "running"
        )
    except Exception as exc:
        db_conn.close()
        raise HTTPException(status_code=500, detail=str(exc))

    if not activities:
        db_conn.close()
        return {
            "status": "success",
            "data": {
                "period": {"from": start_date.isoformat(), "to": today.isoformat()},
                "activitiesCount": 0,
                "saved": [],
            },
        }

    saved_records = []

    for activity in activities:
        activity_id = activity.get("activityId")
        activity_name = activity.get("activityName", "Unknown")
        start_time_local = activity.get("startTimeLocal", "")

        if not start_time_local:
            continue

        try:
            dt = datetime.datetime.strptime(start_time_local, "%Y-%m-%d %H:%M:%S")
            run_date = dt.date()
            run_time = dt.time()
        except ValueError:
            continue

        try:
            hr_zones_data = api.get_activity_hr_in_timezones(activity_id)
            zones_mins = {1: 0.0, 2: 0.0, 3: 0.0, 4: 0.0, 5: 0.0}

            if hr_zones_data and isinstance(hr_zones_data, list):
                for zone_info in hr_zones_data:
                    zone_number = zone_info.get("zoneNumber")
                    secs_in_zone = zone_info.get("secsInZone", 0)
                    if zone_number in zones_mins:
                        zones_mins[zone_number] = secs_in_zone / 60.0

                # Upsert into PostgreSQL
                try:
                    with db_conn.cursor() as cur:
                        cur.execute(
                            """
                            INSERT INTO running_hr_zones
                            (activity_id, activity_name, run_date, run_time,
                             zone_1_mins, zone_2_mins, zone_3_mins, zone_4_mins, zone_5_mins)
                            VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
                            ON CONFLICT (activity_id) DO UPDATE SET
                                activity_name = EXCLUDED.activity_name,
                                zone_1_mins = EXCLUDED.zone_1_mins,
                                zone_2_mins = EXCLUDED.zone_2_mins,
                                zone_3_mins = EXCLUDED.zone_3_mins,
                                zone_4_mins = EXCLUDED.zone_4_mins,
                                zone_5_mins = EXCLUDED.zone_5_mins;
                            """,
                            (
                                activity_id,
                                activity_name,
                                run_date,
                                run_time,
                                round(zones_mins[1], 2),
                                round(zones_mins[2], 2),
                                round(zones_mins[3], 2),
                                round(zones_mins[4], 2),
                                round(zones_mins[5], 2),
                            ),
                        )
                    db_conn.commit()

                    saved_records.append({
                        "activityId": activity_id,
                        "activityName": activity_name,
                        "runDate": run_date.isoformat(),
                        "runTime": run_time.isoformat(),
                        "zone1Mins": round(zones_mins[1], 2),
                        "zone2Mins": round(zones_mins[2], 2),
                        "zone3Mins": round(zones_mins[3], 2),
                        "zone4Mins": round(zones_mins[4], 2),
                        "zone5Mins": round(zones_mins[5], 2),
                    })
                except Exception as exc:
                    db_conn.rollback()
                    log.warning("DB insert error for activity %s: %s", activity_id, exc)
        except Exception as exc:
            log.warning("HR zones fetch error for activity %s: %s", activity_id, exc)

    db_conn.close()

    return {
        "status": "success",
        "data": {
            "period": {"from": start_date.isoformat(), "to": today.isoformat()},
            "activitiesCount": len(activities),
            "savedCount": len(saved_records),
            "saved": saved_records,
        },
    }


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    import uvicorn

    host = os.getenv("API_HOST", "0.0.0.0")
    port = int(os.getenv("API_PORT", "8080"))
    log.info("Starting Garmin API server on %s:%s", host, port)
    uvicorn.run(app, host=host, port=port)
