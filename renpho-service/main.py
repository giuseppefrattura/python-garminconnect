import asyncio
from contextlib import asynccontextmanager, contextmanager
from datetime import datetime, timezone
import logging
import os
import sys
import time

from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, status
from fastapi.middleware.cors import CORSMiddleware
import psycopg2  # type: ignore
from psycopg2 import pool  # type: ignore
from psycopg2.extras import RealDictCursor  # type: ignore
from renpho import RenphoClient

# Load env variables
load_dotenv()

# Setup logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    handlers=[logging.StreamHandler(sys.stdout)],
)
logger = logging.getLogger("renpho-service")

# Configurations
RENPHO_EMAIL = os.getenv("RENPHO_EMAIL")
RENPHO_PASSWORD = os.getenv("RENPHO_PASSWORD")

DB_HOST = os.getenv("DB_HOST", "localhost")
DB_PORT = os.getenv("DB_PORT", "5432")
DB_NAME = os.getenv("DB_NAME", "garmin")
DB_USER = os.getenv("DB_USER", "postgres")
DB_PASSWORD = os.getenv("DB_PASSWORD", "postgres")

# Default daily interval (24 hours)
SYNC_INTERVAL_SECONDS = 24 * 60 * 60

# Global Connection Pool
_db_pool: pool.ThreadedConnectionPool | None = None

# Flag to prevent concurrent manual & scheduled sync executions
_sync_lock = asyncio.Lock()


def init_db_pool():
    """Initialize the ThreadedConnectionPool with retries."""
    global _db_pool
    retries = 5
    while retries > 0:
        try:
            logger.info(f"Initializing connection pool to database {DB_NAME} on {DB_HOST}:{DB_PORT}...")
            _db_pool = pool.ThreadedConnectionPool(
                minconn=1,
                maxconn=10,
                host=DB_HOST,
                port=DB_PORT,
                database=DB_NAME,
                user=DB_USER,
                password=DB_PASSWORD,
            )
            logger.info("Database connection pool established successfully.")
            return
        except Exception as e:
            logger.warning(f"Database connection pool initialization failed: {e}. Retrying in 5 seconds...")
            retries -= 1
            time.sleep(5)
    logger.error("Could not connect to database after several attempts.")


@contextmanager
def get_db_conn():
    """Context manager to borrow a connection from the pool and return it safely."""
    global _db_pool
    if _db_pool is None:
        init_db_pool()
    if _db_pool is None:
        raise RuntimeError("Database connection pool is not available")

    conn = _db_pool.getconn()
    try:
        yield conn
    finally:
        if conn and _db_pool:
            _db_pool.putconn(conn)


def init_db():
    """Ensure the target tables and schema exist in the PostgreSQL instance."""
    query = """
    CREATE TABLE IF NOT EXISTS renpho_measurements (
        id SERIAL PRIMARY KEY,
        time_stamp BIGINT UNIQUE NOT NULL,
        created_at TIMESTAMP WITH TIME ZONE NOT NULL,
        weight NUMERIC(5, 2) NOT NULL,
        bodyfat NUMERIC(4, 2),
        muscle NUMERIC(4, 2),
        bone NUMERIC(4, 2),
        water NUMERIC(4, 2),
        bmi NUMERIC(4, 2),
        resistance INTEGER,
        created_stamp BIGINT,
        synced_to_garmin BOOLEAN DEFAULT FALSE,
        synced_at TIMESTAMP WITH TIME ZONE,
        recorded_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
    );
    """
    with get_db_conn() as conn:
        try:
            with conn.cursor() as cur:
                cur.execute(query)
                # Check if bmr column exists, if not add it
                cur.execute("""
                SELECT column_name 
                FROM information_schema.columns 
                WHERE table_name='renpho_measurements' AND column_name='bmr';
                """)
                if not cur.fetchone():
                    logger.info("Adding 'bmr' column to 'renpho_measurements' table...")
                    cur.execute("ALTER TABLE renpho_measurements ADD COLUMN bmr INTEGER;")
                conn.commit()
            logger.info("Database schema validated/created successfully.")
        except Exception as e:
            conn.rollback()
            logger.error(f"Failed to initialize database schema: {e}")
            raise


def sync_renpho() -> dict:
    """Connect to Renpho, pull new weigh-ins, and persist to PostgreSQL."""
    if not RENPHO_EMAIL or not RENPHO_PASSWORD:
        err_msg = "RENPHO_EMAIL or RENPHO_PASSWORD environment variables are missing!"
        logger.error(err_msg)
        return {"status": "error", "detail": err_msg}

    logger.info("Starting Renpho Health synchronization...")
    try:
        logger.info(f"Logging in to Renpho Health account: {RENPHO_EMAIL}...")
        client = RenphoClient(RENPHO_EMAIL, RENPHO_PASSWORD)
        client.login()
        logger.info("Renpho authentication successful.")

        logger.info("Fetching measurements list from Renpho...")
        measurements = client.get_all_measurements()
        logger.info(f"Retrieved {len(measurements)} total measurements from Renpho history.")

        new_records_count = 0
        with get_db_conn() as conn:
            with conn.cursor() as cur:
                for m in measurements:
                    raw_timestamp = m.get("timeStamp") or m.get("time_stamp") or m.get("created_stamp")
                    if not raw_timestamp:
                        continue

                    time_stamp = int(raw_timestamp)
                    created_at = datetime.fromtimestamp(time_stamp, tz=timezone.utc)
                    weight = m.get("weight")
                    if weight is None:
                        continue

                    query = """
                    INSERT INTO renpho_measurements (
                        time_stamp, created_at, weight, bodyfat, muscle, bone, water, bmi, resistance, created_stamp, bmr
                    ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                    ON CONFLICT (time_stamp) DO UPDATE SET bmr = EXCLUDED.bmr;
                    """

                    cur.execute(
                        query,
                        (
                            time_stamp,
                            created_at,
                            weight,
                            m.get("bodyfat"),
                            m.get("muscle"),
                            m.get("bone"),
                            m.get("water"),
                            m.get("bmi"),
                            m.get("resistance"),
                            time_stamp,
                            m.get("bmr"),
                        ),
                    )

                    if cur.rowcount > 0:
                        new_records_count += 1

                conn.commit()
            logger.info(f"Sync complete. Processed/added {new_records_count} measurements.")
            return {"status": "success", "syncedCount": new_records_count, "totalCount": len(measurements)}

    except Exception as e:
        logger.error(f"An error occurred during Renpho synchronization: {e}", exc_info=True)
        return {"status": "error", "detail": str(e)}


# ---------------------------------------------------------------------------
# Background Async Scheduler Tasks
# ---------------------------------------------------------------------------
async def daily_sync_loop():
    """Daily synchronization daemon loop."""
    logger.info("Background daily sync scheduler task initialized.")
    # Sleep 15s initially to avoid database locks during initial container start
    await asyncio.sleep(15)
    while True:
        try:
            async with _sync_lock:
                logger.info("Executing scheduled background Renpho sync...")
                res = await asyncio.to_thread(sync_renpho)
                logger.info(f"Scheduled sync outcome: {res}")
        except Exception as e:
            logger.error(f"Error executing background sync task: {e}")

        logger.info(f"Scheduled sync sleep sequence starting. Next sync in {SYNC_INTERVAL_SECONDS}s.")
        await asyncio.sleep(SYNC_INTERVAL_SECONDS)


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Startup: initialize connection pool and DB schema once
    try:
        init_db_pool()
        init_db()
    except Exception as e:
        logger.warning(f"Initial DB pool or schema setup deferred: {e}")

    # Startup: spawn the background daily sync loop task
    sync_task = asyncio.create_task(daily_sync_loop())
    yield
    # Shutdown: cancel task and close pool
    sync_task.cancel()
    if _db_pool:
        _db_pool.closeall()
        logger.info("Database connection pool closed.")


# ---------------------------------------------------------------------------
# FastAPI Application Configuration
# ---------------------------------------------------------------------------
app = FastAPI(
    title="Renpho Weight Sync Service",
    description="Microservice to pull Renpho Health data and persist measurements in PostgreSQL.",
    version="1.0.0",
    lifespan=lifespan,
)

ALLOWED_ORIGINS = [
    o.strip() for o in os.getenv("RENPHO_ALLOWED_ORIGINS", "http://localhost:8081").split(",")
    if o.strip()
]

app.add_middleware(
    CORSMiddleware,
    allow_origins=ALLOWED_ORIGINS,
    allow_credentials=True,
    allow_methods=["GET", "POST"],
    allow_headers=["Content-Type", "Authorization", "X-API-Key"],
)


@app.get("/health", summary="Health check")
async def health():
    return {"status": "ok", "renpho_credentials_configured": bool(RENPHO_EMAIL and RENPHO_PASSWORD)}


@app.get("/api/renpho/measurements", summary="Get all weigh-ins from DB")
async def get_measurements():
    """Retrieve all persisted measurements from PostgreSQL database, ordered by date descending."""
    try:
        with get_db_conn() as conn:
            with conn.cursor(cursor_factory=RealDictCursor) as cur:
                cur.execute("SELECT * FROM renpho_measurements ORDER BY created_at DESC;")
                records = cur.fetchall()
                return records
    except Exception as e:
        logger.error(f"Failed to fetch measurements: {e}")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Database query error: {str(e)}",
        )


@app.post("/api/renpho/sync", summary="Trigger manual Renpho sync")
async def trigger_sync():
    """Execute manual synchronization and return immediate stats."""
    if _sync_lock.locked():
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="A synchronization process is already running in background.",
        )

    async with _sync_lock:
        logger.info("Executing manual trigger synchronization...")
        result = await asyncio.to_thread(sync_renpho)

        if result["status"] == "error":
            raise HTTPException(
                status_code=status.HTTP_502_BAD_GATEWAY,
                detail=result.get("detail", "Sync failed."),
            )

        return result
