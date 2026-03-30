#!/usr/bin/env python3
"""
Script to fetch and display the details of the most recent
strength/weight training activity from Garmin Connect.
"""

import os
import sys
import logging
import datetime

from garminconnect import (
    Garmin,
    GarminConnectAuthenticationError,
    GarminConnectConnectionError,
)
from garth.exc import GarthHTTPError

# Set logging level to reduce noise
logging.getLogger("garminconnect").setLevel(logging.CRITICAL)

def format_duration(seconds: float) -> str:
    """Format duration in seconds to HH:MM:SS."""
    if not seconds:
        return "00:00:00"
    return str(datetime.timedelta(seconds=int(seconds)))

def init_api() -> Garmin | None:
    tokenstore = os.getenv("GARMINTOKENS") or "~/.garminconnect"
    print(f"🔄 Authenticating using stored tokens from: {tokenstore}")
    
    try:
        api = Garmin()
        api.login(tokenstore)
        print("✅ Successfully authenticated!\n")
        return api
    except (
        FileNotFoundError,
        GarthHTTPError,
        GarminConnectAuthenticationError,
        GarminConnectConnectionError,
    ):
        print("❌ Authentication failed. Please run demo.py first to login and save tokens.")
        return None

def main():
    api = init_api()
    if not api:
        sys.exit(1)
        
    print("🔍 Searching for the last strength training activity...")
    
    # Fetch the last 30 activities to ensure we find a strength workout
    try:
        activities = api.get_activities(0, 30)
    except Exception as e:
        print(f"❌ Error fetching activities: {e}")
        sys.exit(1)
        
    strength_activity = None
    for activity in activities:
        activity_type = activity.get("activityType", {}).get("typeKey", "")
        # Garmin often uses "strength_training" or "fitness_equipment"
        if "strength" in activity_type.lower() or "training" in activity_type.lower():
            strength_activity = activity
            break
            
    if not strength_activity:
        print("❌ No strength training activity found in the last 30 activities.")
        sys.exit(0)
        
    activity_id = strength_activity.get("activityId")
    activity_name = strength_activity.get("activityName", "Unknown")
    start_time = strength_activity.get("startTimeLocal", "Unknown")
    
    # Extract general stats
    duration = strength_activity.get("duration", 0)
    calories = strength_activity.get("calories", 0)
    avg_hr = strength_activity.get("averageHR", "N/A")
    max_hr = strength_activity.get("maxHR", "N/A")
    aerobic_te = strength_activity.get("aerobicTrainingEffect", "N/A")
    anaerobic_te = strength_activity.get("anaerobicTrainingEffect", "N/A")
    
    print("=" * 60)
    print(f"🏋️  LATEST STRENGTH TRAINING: {activity_name}")
    print(f"📅 Date & Time  : {start_time}")
    print(f"⏱️  Duration     : {format_duration(duration)}")
    print(f"🔥 Calories     : {calories} kcal")
    print(f"❤️  Heart Rate   : Avg {avg_hr} bpm | Max {max_hr} bpm")
    print(f"📊 Effect       : Aerobic {aerobic_te} | Anaerobic {anaerobic_te}")
    print("=" * 60)
    
    print("\n📋 EXERCISE SETS DETAILS:")
    print("-" * 60)
    
    try:
        # Fetch the specific exercise sets
        sets_data = api.get_activity_exercise_sets(activity_id)
        
        if not sets_data or not isinstance(sets_data, dict) or "exerciseSets" not in sets_data:
             print("No detailed sets data found for this activity.")
        else:
            exercise_sets = sets_data.get("exerciseSets", [])
            
            if not exercise_sets:
                print("No sets recorded.")
            else:
                print(f"{'Set':<5} | {'Exercise':<30} | {'Reps':<5} | {'Weight (kg)':<10}")
                print("-" * 60)
                
                # Mapping from Garmin exercise categories to actual muscle groups
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
                    "FARMERS_WALK": "Full Body/Core"
                }

                # Track volume per muscle group
                volume_by_group = {}
                
                # Filter out pure rest periods to only show actual work sets
                set_num = 1
                for ex_set in exercise_sets:
                    set_type = ex_set.get("setType", "")
                    
                    # Usually setType is "REST" or "ACTIVE"
                    if set_type == "REST":
                        continue
                        
                    # Get exercise name, repetitions, and weight
                    exercises = ex_set.get("exercises", [])
                    ex_name = "Unknown Exercise"
                    if exercises and isinstance(exercises, list) and len(exercises) > 0:
                        first_ex = exercises[0]
                        cat = first_ex.get("category", "")
                        name = first_ex.get("name")
                        
                        if name:
                            ex_name = name.replace("_", " ").title()
                        elif cat:
                            ex_name = cat.replace("_", " ").title()
                    
                    reps = ex_set.get("repetitionCount") or 0
                    weight = ex_set.get("weight", 0.0)
                    
                    # Weight could be -1.0 or similar if bodyweight
                    if weight and weight > 0:
                        weight_kg = weight / 1000.0
                    else:
                        weight_kg = 0.0
                    
                    # Ignore empty sets (0 reps, 0 weight) unless they actually have a valid exercise
                    if reps > 0 or weight_kg > 0 or ex_name != "Unknown Exercise":
                        print(f"#{set_num:<4} | {ex_name[:28]:<30} | {reps:<5} | {weight_kg:<10.1f}")
                        set_num += 1
                        
                        # Calculate volume
                        if weight_kg > 0 and reps > 0:
                            # Use category for muscular group, fallback to exercise name if not available
                            muscular_group = "Unknown"
                            if exercises and isinstance(exercises, list) and len(exercises) > 0:
                                cat = exercises[0].get("category")
                                if cat:
                                    # Map to actual muscle group if known, else use the raw category Title Cased
                                    muscular_group = MUSCLE_GROUP_MAP.get(cat, cat.replace("_", " ").title())
                                else:
                                    muscular_group = ex_name
                            else:
                                muscular_group = ex_name
                            
                            volume_by_group[muscular_group] = volume_by_group.get(muscular_group, 0.0) + (reps * weight_kg)

                print("\n📊 TRAINING VOLUME BY MUSCULAR GROUP:")
                print("-" * 60)
                if not volume_by_group:
                    print("No volume data available (no weights/reps recorded).")
                else:
                    print(f"{'Muscular Group':<30} | {'Total Volume (kg)':<20}")
                    print("-" * 60)
                    for group, volume in sorted(volume_by_group.items(), key=lambda x: x[1], reverse=True):
                        print(f"{group:<30} | {volume:<20.1f}")

    except Exception as e:
        print(f"❌ Error fetching exercise sets: {e}")
        
    print("\n" + "=" * 60)

if __name__ == "__main__":
    main()
