#!/usr/bin/env python3
"""
Garmin Proxy — thin FastAPI wrapper around the garminconnect library.

This service contains ZERO business logic. It authenticates once with
Garmin Connect and exposes raw data via REST endpoints so that other
services (e.g. a Spring Boot app) can consume Garmin data over HTTP.

Swagger UI:  GET /docs
OpenAPI JSON: GET /openapi.json
"""

import hashlib
import logging
import os
import secrets
import threading
import time
from contextlib import asynccontextmanager

from dotenv import load_dotenv
from fastapi import Depends, FastAPI, HTTPException, Query, Request, Security, status
from fastapi.responses import RedirectResponse
from fastapi.security import APIKeyHeader
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

_garmin_api: Garmin | None = None


# ---------------------------------------------------------------------------
# Caching & Security Definitions
# ---------------------------------------------------------------------------
class InMemoryCache:
    def __init__(self, ttl_seconds: int = 3600):
        self.ttl = ttl_seconds
        self.cache = {}
        self.lock = threading.Lock()

    def get(self, key: str):
        with self.lock:
            if key not in self.cache:
                return None
            expiry, value = self.cache[key]
            if time.time() > expiry:
                del self.cache[key]
                return None
            return value

    def set(self, key: str, value):
        with self.lock:
            if len(self.cache) > 200:
                now = time.time()
                expired = [k for k, (exp, _) in self.cache.items() if now > exp]
                for k in expired:
                    del self.cache[k]
            expiry = time.time() + self.ttl
            self.cache[key] = (expiry, value)

    def clear(self):
        with self.lock:
            self.cache.clear()


# Cache instance with 1 hour TTL
_cache = InMemoryCache(ttl_seconds=3600)

API_KEY_NAME = "X-API-Key"
api_key_header = APIKeyHeader(name=API_KEY_NAME, auto_error=False)


def verify_api_key(api_key: str = Security(api_key_header)):
    """Verify X-API-Key header against GARMIN_API_KEY env var.

    API key auth is mandatory outside of explicit development mode.
    Set GARMIN_ALLOW_INSECURE_DEV=1 to bypass in local dev only.
    """
    expected_key = os.getenv("GARMIN_API_KEY")
    allow_insecure_dev = os.getenv("GARMIN_ALLOW_INSECURE_DEV", "").lower() in ("1", "true", "yes")

    if not expected_key or not expected_key.strip():
        if allow_insecure_dev:
            log.warning("GARMIN_API_KEY unset and GARMIN_ALLOW_INSECURE_DEV=1: API auth BYPASSED (dev only).")
            return
        log.error("GARMIN_API_KEY is not configured. Refusing to start in production mode.")
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Server is not configured for API authentication. Set GARMIN_API_KEY.",
        )
    if not secrets.compare_digest(api_key or "", expected_key):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or missing API Key",
        )


# ---------------------------------------------------------------------------
# Garmin Core Authentication
# ---------------------------------------------------------------------------
def _authenticate(force: bool = False) -> Garmin:
    """Authenticate with Garmin and return the API instance.

    Args:
        force: If True, discard the cached instance and re-authenticate.
    """
    global _garmin_api
    if _garmin_api is not None and not force:
        return _garmin_api

    if force:
        log.info("Forcing Garmin re-authentication (token may have expired).")
        _garmin_api = None

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


def _get_api() -> Garmin:
    """Return the authenticated Garmin instance, raising HTTP 503 on failure."""
    try:
        return _authenticate()
    except RuntimeError as exc:
        raise HTTPException(status_code=503, detail=str(exc))


def _call_with_reauth(fn):
    """Call a Garmin API function; on GarthHTTPError, re-authenticate once and retry."""
    try:
        return fn(_get_api())
    except GarthHTTPError:
        log.warning("Garmin API call failed (possible token expiry), re-authenticating…")
        try:
            _authenticate(force=True)
        except RuntimeError as exc:
            raise HTTPException(status_code=503, detail=str(exc))
        return fn(_get_api())
    except (
        GarminConnectAuthenticationError,
        GarminConnectConnectionError,
    ) as exc:
        log.warning("Garmin API call failed: %s — attempting re-auth…", exc)
        try:
            _authenticate(force=True)
        except RuntimeError as re_exc:
            raise HTTPException(status_code=503, detail=str(re_exc))
        return fn(_get_api())


# ---------------------------------------------------------------------------
# Lifespan
# ---------------------------------------------------------------------------
@asynccontextmanager
async def lifespan(_app: FastAPI):
    log.info("Garmin proxy starting up…")
    try:
        _authenticate()
    except RuntimeError:
        log.warning("Garmin auth failed at startup – will retry on first request.")
    yield
    log.info("Garmin proxy shutting down.")


# ---------------------------------------------------------------------------
# App
# ---------------------------------------------------------------------------
app = FastAPI(
    title="Garmin Proxy",
    description="Thin REST proxy exposing raw Garmin Connect data.",
    version="1.0.0",
    lifespan=lifespan,
)

# Instrument FastAPI app to expose Prometheus metrics on /metrics
Instrumentator().instrument(app).expose(app)


@app.middleware("http")
async def log_requests(request: Request, call_next):
    start = time.time()
    response = await call_next(request)
    elapsed = time.time() - start
    log.info("%s %s - %s - %.4fs", request.method, request.url.path,
             response.status_code, elapsed)
    response.headers["X-Process-Time"] = str(elapsed)
    return response


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------
@app.get("/", include_in_schema=False)
async def root():
    return RedirectResponse(url="/docs")


@app.get("/health", summary="Health check", tags=["System"])
async def health():
    return {"status": "ok", "garmin_authenticated": _garmin_api is not None}


@app.get(
    "/api/activities",
    summary="List recent activities",
    tags=["Activities"],
    dependencies=[Security(verify_api_key)],
)
async def get_activities(
    start: int = Query(0, description="Start index"),
    limit: int = Query(30, description="Max activities to return"),
    bypass_cache: bool = Query(False, description="Bypass cache and force refresh"),
):
    """Return a list of recent activities from Garmin Connect."""
    cache_key = f"activities:{start}:{limit}"
    if not bypass_cache:
        cached_val = _cache.get(cache_key)
        if cached_val is not None:
            log.info("Cache hit for key: %s", cache_key)
            return cached_val

    val = _call_with_reauth(lambda api: api.get_activities(start, limit))
    _cache.set(cache_key, val)
    return val


@app.get(
    "/api/activities/by-date",
    summary="Activities by date range",
    tags=["Activities"],
    dependencies=[Security(verify_api_key)],
)
async def get_activities_by_date(
    start: str = Query(..., description="Start date (YYYY-MM-DD)"),
    end: str = Query(..., description="End date (YYYY-MM-DD)"),
    activity_type: str = Query("", description="Activity type filter (e.g. 'running')"),
    bypass_cache: bool = Query(False, description="Bypass cache and force refresh"),
):
    """Return activities within a date range, optionally filtered by type."""
    cache_key = f"activities_by_date:{start}:{end}:{activity_type}"
    if not bypass_cache:
        cached_val = _cache.get(cache_key)
        if cached_val is not None:
            log.info("Cache hit for key: %s", cache_key)
            return cached_val

    val = _call_with_reauth(
        lambda api: api.get_activities_by_date(start, end, activity_type or None)
    )
    _cache.set(cache_key, val)
    return val


@app.get(
    "/api/activities/{activity_id}/exercise-sets",
    summary="Exercise sets for an activity",
    tags=["Activities"],
    dependencies=[Security(verify_api_key)],
)
async def get_exercise_sets(
    activity_id: int,
    bypass_cache: bool = Query(False, description="Bypass cache and force refresh"),
):
    """Return exercise set details (sets, reps, weight) for a given activity."""
    cache_key = f"exercise_sets:{activity_id}"
    if not bypass_cache:
        cached_val = _cache.get(cache_key)
        if cached_val is not None:
            log.info("Cache hit for key: %s", cache_key)
            return cached_val

    val = _call_with_reauth(lambda api: api.get_activity_exercise_sets(activity_id))
    _cache.set(cache_key, val)
    return val


@app.get(
    "/api/activities/{activity_id}/hr-zones",
    summary="HR zones for an activity",
    tags=["Activities"],
    dependencies=[Security(verify_api_key)],
)
async def get_hr_zones(
    activity_id: int,
    bypass_cache: bool = Query(False, description="Bypass cache and force refresh"),
):
    """Return heart-rate zone time breakdown for a given activity."""
    cache_key = f"hr_zones:{activity_id}"
    if not bypass_cache:
        cached_val = _cache.get(cache_key)
        if cached_val is not None:
            log.info("Cache hit for key: %s", cache_key)
            return cached_val

    val = _call_with_reauth(lambda api: api.get_activity_hr_in_timezones(activity_id))
    _cache.set(cache_key, val)
    return val


@app.get(
    "/api/health/daily-summary",
    summary="Daily aggregated health and recovery data",
    tags=["Health"],
    dependencies=[Security(verify_api_key)],
)
async def get_daily_health_summary(
    date: str = Query(..., description="Date (YYYY-MM-DD)"),
    bypass_cache: bool = Query(False, description="Bypass cache and force refresh"),
):
    """Return aggregated sleep, body battery, HRV, and stress data for a specific date."""
    cache_key = f"daily_health_summary:{date}"
    if not bypass_cache:
        cached_val = _cache.get(cache_key)
        if cached_val is not None:
            log.info("Cache hit for key: %s", cache_key)
            return cached_val

    def _fetch_all(api: Garmin):
        summary = {"date": date, "sleep": None, "body_battery": None, "hrv": None, "stress": None}
        try:
            summary["sleep"] = api.get_sleep_data(date)
        except Exception as e:
            log.warning("Failed to fetch sleep for %s: %s", date, e)

        try:
            bb = api.get_body_battery(date)
            summary["body_battery"] = bb
        except Exception as e:
            log.warning("Failed to fetch body battery for %s: %s", date, e)

        try:
            summary["hrv"] = api.get_hrv_data(date)
        except Exception as e:
            log.warning("Failed to fetch HRV for %s: %s", date, e)

        try:
            summary["stress"] = api.get_all_day_stress(date)
        except Exception as e:
            log.warning("Failed to fetch stress for %s: %s", date, e)

        return summary

    val = _call_with_reauth(_fetch_all)
    _cache.set(cache_key, val)
    return val


@app.get(
    "/api/health/sleep",
    summary="Sleep data for a date",
    tags=["Health"],
    dependencies=[Security(verify_api_key)],
)
async def get_sleep_data(
    date: str = Query(..., description="Date (YYYY-MM-DD)"),
    bypass_cache: bool = Query(False, description="Bypass cache and force refresh"),
):
    """Return detailed sleep data and sleep stages for a date."""
    cache_key = f"sleep:{date}"
    if not bypass_cache:
        cached_val = _cache.get(cache_key)
        if cached_val is not None:
            return cached_val

    val = _call_with_reauth(lambda api: api.get_sleep_data(date))
    _cache.set(cache_key, val)
    return val


@app.get(
    "/api/health/body-battery",
    summary="Body Battery for a date",
    tags=["Health"],
    dependencies=[Security(verify_api_key)],
)
async def get_body_battery(
    date: str = Query(..., description="Date (YYYY-MM-DD)"),
    bypass_cache: bool = Query(False, description="Bypass cache and force refresh"),
):
    """Return Body Battery timeline and events for a date."""
    cache_key = f"body_battery:{date}"
    if not bypass_cache:
        cached_val = _cache.get(cache_key)
        if cached_val is not None:
            return cached_val

    val = _call_with_reauth(lambda api: api.get_body_battery(date))
    _cache.set(cache_key, val)
    return val


@app.get(
    "/api/health/hrv",
    summary="HRV status and nightly data",
    tags=["Health"],
    dependencies=[Security(verify_api_key)],
)
async def get_hrv_data(
    date: str = Query(..., description="Date (YYYY-MM-DD)"),
    bypass_cache: bool = Query(False, description="Bypass cache and force refresh"),
):
    """Return Heart Rate Variability (HRV) metrics and baseline for a date."""
    cache_key = f"hrv:{date}"
    if not bypass_cache:
        cached_val = _cache.get(cache_key)
        if cached_val is not None:
            return cached_val

    val = _call_with_reauth(lambda api: api.get_hrv_data(date))
    _cache.set(cache_key, val)
    return val


@app.get(
    "/api/health/stress",
    summary="All-day stress data",
    tags=["Health"],
    dependencies=[Security(verify_api_key)],
)
async def get_stress_data(
    date: str = Query(..., description="Date (YYYY-MM-DD)"),
    bypass_cache: bool = Query(False, description="Bypass cache and force refresh"),
):
    """Return all-day stress breakdown and metrics for a date."""
    cache_key = f"stress:{date}"
    if not bypass_cache:
        cached_val = _cache.get(cache_key)
        if cached_val is not None:
            return cached_val

    val = _call_with_reauth(lambda api: api.get_all_day_stress(date))
    _cache.set(cache_key, val)
    return val


@app.post(
    "/api/cache/clear",
    summary="Clear the in-memory cache",
    tags=["System"],
    dependencies=[Security(verify_api_key)],
)
async def clear_cache():
    """Clear all cached entries."""
    _cache.clear()
    log.info("In-memory cache cleared successfully.")
    return {"status": "success", "detail": "Cache cleared."}


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    import uvicorn

    host = os.getenv("API_HOST", "0.0.0.0")
    port = int(os.getenv("API_PORT", "8080"))
    log.info("Starting Garmin proxy on %s:%s", host, port)
    uvicorn.run(app, host=host, port=port)

