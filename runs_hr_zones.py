#!/usr/bin/env python3
"""
Script to download running activities of the last 10 days
and aggregate the total minutes spent in each heart rate zone.
"""

import datetime
import os
import sys
import logging
from datetime import timedelta

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

def main():
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
        
    print(f"Found {len(activities)} running activities. Fetching HR zones...\n")
    
    aggregated_zones = {}
    
    for activity in activities:
        activity_id = activity.get("activityId")
        activity_name = activity.get("activityName", "Unknown")
        start_time = activity.get("startTimeLocal", "Unknown Date")
        
        print(f"- Processing run: {activity_name} ({start_time})")
        
        try:
            hr_zones_data = api.get_activity_hr_in_timezones(activity_id)
            if hr_zones_data and isinstance(hr_zones_data, list):
                # The returned data is a list of zone information
                for zone_info in hr_zones_data:
                    zone_number = zone_info.get("zoneNumber")
                    secs_in_zone = zone_info.get("secsInZone", 0)
                    
                    if zone_number is not None:
                        aggregated_zones[zone_number] = aggregated_zones.get(zone_number, 0) + secs_in_zone
            else:
                 print(f"  ⚠️ Could not fetch HR zones for {activity_name} (No compatible data)")
        except Exception as e:
             print(f"  ⚠️ Error fetching HR zones for {activity_name}: {e}")
             
    print("\n" + "="*50)
    print("📊 AGGREGATED HEART RATE ZONES (Last 10 Days)")
    print("="*50)
    
    if not aggregated_zones:
        print("No HR zone data could be aggregated.")
    else:
        # Sort by zone number
        for zone_number in sorted(aggregated_zones.keys()):
            total_secs = aggregated_zones[zone_number]
            total_mins = total_secs / 60.0
            print(f"Zone {zone_number}: {total_mins:.1f} minutes")
            
    print("="*50)

if __name__ == "__main__":
    main()
