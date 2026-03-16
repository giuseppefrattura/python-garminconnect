#!/usr/bin/env python3
"""
Script to download running activities of the last 10 days,
extract minutes spent in each heart rate zone, and save them
to a PostgreSQL database.
"""

import datetime
import os
import sys
import logging
import psycopg2
from datetime import timedelta
from dotenv import load_dotenv

# Load environment variables from .env file if it exists
load_dotenv()

from garminconnect import (
    Garmin,
    GarminConnectAuthenticationError,
    GarminConnectConnectionError,
)
from garth.exc import GarthHTTPError

# Configure logging to reduce verbose error output
logging.getLogger("garminconnect").setLevel(logging.CRITICAL)

def init_api() -> Garmin | None:
    tokenstore = os.getenv("GARMINTOKENS") or "~/.garminconnect"
    print(f"Attempting to login using stored tokens from: {tokenstore}")
    
    try:
        garmin = Garmin()
        garmin.login(tokenstore)
        print("✅ Successfully logged in using stored tokens!")
        return garmin
    except (
        FileNotFoundError,
        GarthHTTPError,
        GarminConnectAuthenticationError,
        GarminConnectConnectionError,
    ):
        print("❌ No valid tokens found. Please run demo.py first to authenticate and store your tokens.")
        return None

def connect_db():
    try:
        # Usa variabili d'ambiente per la connessione, con valori test/default.
        # È possibile impostarle prima di eseguire lo script (es. export DB_HOST=localhost)
        conn = psycopg2.connect(
            host=os.getenv("DB_HOST", "localhost"),
            database=os.getenv("DB_NAME", "garmin"),
            user=os.getenv("DB_USER", "postgres"),
            password=os.getenv("DB_PASSWORD", "postgres"),
            port=os.getenv("DB_PORT", "5432")
        )
        return conn
    except Exception as e:
        print(f"❌ Error connecting to PostgreSQL database: {e}")
        print("Assicurati di aver impostato le variabili d'ambiente corrette: DB_HOST, DB_NAME, DB_USER, DB_PASSWORD, DB_PORT")
        sys.exit(1)

def create_table_if_not_exists(conn):
    try:
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
    except Exception as e:
        print(f"❌ Error creating table: {e}")
        print("💡 The script automatically tries to create the table, but it seems your database user lacks permissions.")
        print(f"   Make sure to grant the necessary permissions, e.g.: GRANT CREATE ON SCHEMA public TO {os.getenv('DB_USER', 'postgres')};")
        conn.rollback()
        sys.exit(1)

def main():
    # 1. Connessione al DB e setup tabella
    db_conn = connect_db()
    create_table_if_not_exists(db_conn)
    print("✅ Connected to PostgreSQL and verified table exists.")

    # 2. Setup Garmin API
    api = init_api()
    if not api:
        sys.exit(1)
        
    today = datetime.date.today()
    start_date = today - timedelta(days=10)
    
    start_str = start_date.isoformat()
    end_str = today.isoformat()
    
    print(f"\n🏃 Fetching running activities from {start_str} to {end_str}...")
    
    try:
        activities = api.get_activities_by_date(start_str, end_str, "running")
    except Exception as e:
        print(f"❌ Error fetching activities: {e}")
        sys.exit(1)
        
    if not activities:
        print("No running activities found in the last 10 days.")
        sys.exit(0)
        
    print(f"Found {len(activities)} running activities. Processing HR zones...\n")
    
    inserted_count = 0

    for activity in activities:
        activity_id = activity.get("activityId")
        activity_name = activity.get("activityName", "Unknown")
        start_time_local = activity.get("startTimeLocal", "")
        
        print(f"\n- Processing run: {activity_name} (ID: {activity_id})")
        
        if not start_time_local:
            print("  ⚠️ No start time found. Skipping.")
            continue
            
        try:
            # Parse datetime string like "2024-03-01 10:20:30"
            dt = datetime.datetime.strptime(start_time_local, "%Y-%m-%d %H:%M:%S")
            run_date = dt.date()
            run_time = dt.time()
        except ValueError:
            print(f"  ⚠️ Could not parse start time '{start_time_local}'. Skipping.")
            continue
            
        try:
            hr_zones_data = api.get_activity_hr_in_timezones(activity_id)
            
            # Initialize zone minutes
            zones_mins = {1: 0.0, 2: 0.0, 3: 0.0, 4: 0.0, 5: 0.0}
            
            if hr_zones_data and isinstance(hr_zones_data, list):
                for zone_info in hr_zones_data:
                    zone_number = zone_info.get("zoneNumber")
                    secs_in_zone = zone_info.get("secsInZone", 0)
                    
                    if zone_number in zones_mins:
                        zones_mins[zone_number] = secs_in_zone / 60.0
                        
                # Create the tuple as requested
                # (date, time, z1, z2, z3, z4, z5)
                run_tuple = (
                    run_date,
                    run_time,
                    round(zones_mins[1], 2),
                    round(zones_mins[2], 2),
                    round(zones_mins[3], 2),
                    round(zones_mins[4], 2),
                    round(zones_mins[5], 2)
                )
                
                print(f"  ✅ Data tuple created: {run_tuple}")
                
                # Insert into PostgreSQL (using UPSERT to update if activity_id already exists)
                try:
                    with db_conn.cursor() as cur:
                        cur.execute("""
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
                        """, (
                            activity_id,
                            activity_name,
                            run_tuple[0],
                            run_tuple[1],
                            run_tuple[2],
                            run_tuple[3],
                            run_tuple[4],
                            run_tuple[5],
                            run_tuple[6]
                        ))
                    db_conn.commit()
                    print(f"  ✅ Saved/Updated in PostgreSQL.")
                    inserted_count += 1
                except Exception as e:
                    print(f"  ❌ Error inserting into database: {e}")
                    db_conn.rollback()

            else:
                 print(f"  ⚠️ Could not fetch HR zones for {activity_name} (No compatible data)")
        except Exception as e:
             print(f"  ⚠️ Error fetching HR zones for {activity_name}: {e}")
             
    print("\n" + "="*50)
    print(f"✅ Process completed. Saved/updated {inserted_count} runs to PostgreSQL.")
    print("="*50)

    db_conn.close()

if __name__ == "__main__":
    main()
