#!/usr/bin/env python3
"""🏃‍♂️ Simple Garmin Connect API Example.
=====================================

This example demonstrates the basic usage of python-garminconnect:
- Authentication with email/password
- Token storage and automatic reuse
- MFA (Multi-Factor Authentication) support
- Comprehensive error handling for all API calls
- Basic API calls for user stats

For a comprehensive demo of all available API calls, see demo.py

Dependencies:
pip3 install garth requests

Environment Variables (optional):
export EMAIL=<your garmin email address>
export PASSWORD=<your garmin password>
export GARMINTOKENS=<path to token storage>
"""

import logging
import os
import sys
from datetime import date
from getpass import getpass
from pathlib import Path

import requests
from garth.exc import GarthException, GarthHTTPError

from garminconnect import (
    Garmin,
    GarminConnectAuthenticationError,
    GarminConnectConnectionError,
    GarminConnectTooManyRequestsError,
)

# Suppress garminconnect library logging to avoid tracebacks in normal operation
logging.getLogger("garminconnect").setLevel(logging.CRITICAL)


_GARTH_HTTP_MESSAGES = (
    (400, "Endpoint not available (400 Bad Request) - Feature may not be enabled for your account"),
    (401, "Authentication required (401 Unauthorized) - Please re-authenticate"),
    (403, "Access denied (403 Forbidden) - Account may not have permission"),
    (404, "Endpoint not found (404) - Feature may have been moved or removed"),
    (429, "Rate limit exceeded (429) - Please wait before making more requests"),
    (500, "Server error (500) - Garmin's servers are experiencing issues"),
    (503, "Service unavailable (503) - Garmin's servers are temporarily unavailable"),
)


def _format_garth_http_error(e: GarthHTTPError) -> str:
    error_str = str(e)
    status_code = getattr(getattr(e, "response", None), "status_code", None)
    for code, message in _GARTH_HTTP_MESSAGES:
        if status_code == code or str(code) in error_str:
            return message
    return f"HTTP error: {e}"


def safe_api_call(api_method, *args, **kwargs):
    """Safe API call wrapper with comprehensive error handling.

    This demonstrates the error handling patterns used throughout the library.
    Returns (success: bool, result: Any, error_message: str)
    """
    try:
        result = api_method(*args, **kwargs)
        return True, result, None

    except GarthHTTPError as e:
        return False, None, _format_garth_http_error(e)

    except FileNotFoundError:
        return (
            False,
            None,
            "No valid tokens found. Please login with your email/password to create new tokens.",
        )

    except GarminConnectAuthenticationError as e:
        return False, None, f"Authentication issue: {e}"

    except GarminConnectConnectionError as e:
        return False, None, f"Connection issue: {e}"

    except GarminConnectTooManyRequestsError as e:
        return False, None, f"Rate limit exceeded: {e}"

    except Exception as e:
        return False, None, f"Unexpected error: {e}"


def get_credentials():
    """Get email and password from environment or user input."""
    email = os.getenv("EMAIL")
    password = os.getenv("PASSWORD")

    if not email:
        email = input("Login email: ")
    if not password:
        password = getpass("Enter password: ")

    return email, password


def _try_login_with_stored_tokens(tokenstore_path: Path) -> Garmin | None:
    try:
        garmin = Garmin()
        garmin.login(str(tokenstore_path))
        return garmin
    except (
        FileNotFoundError,
        GarthHTTPError,
        GarminConnectAuthenticationError,
        GarminConnectConnectionError,
    ):
        return None


def _complete_mfa_challenge(garmin: Garmin, mfa_state) -> bool:
    mfa_code = input("Please enter your MFA code: ")
    try:
        garmin.resume_login(mfa_state, mfa_code)
        return True
    except GarthHTTPError as garth_error:
        error_str = str(garth_error)
        if "429" in error_str and "Too Many Requests" in error_str:
            sys.exit(1)
        if "401" in error_str or "403" in error_str:
            return False
        sys.exit(1)
    except GarthException:
        return False


def init_api() -> Garmin | None:
    """Initialize Garmin API with authentication and token management."""
    tokenstore = os.getenv("GARMINTOKENS", "~/.garminconnect")
    tokenstore_path = Path(tokenstore).expanduser()

    garmin = _try_login_with_stored_tokens(tokenstore_path)
    if garmin is not None:
        return garmin

    while True:
        try:
            email, password = get_credentials()
            garmin = Garmin(
                email=email, password=password, is_cn=False, return_on_mfa=True
            )
            result1, result2 = garmin.login()

            if result1 == "needs_mfa" and not _complete_mfa_challenge(garmin, result2):
                continue

            garmin.garth.dump(str(tokenstore_path))
            return garmin

        except GarminConnectAuthenticationError:
            continue

        except (
            FileNotFoundError,
            GarthHTTPError,
            GarminConnectConnectionError,
            requests.exceptions.HTTPError,
        ):
            return None

        except KeyboardInterrupt:
            return None


def display_user_info(api: Garmin):
    """Display basic user information with proper error handling."""
    # Get user's full name
    success, _full_name, _error_msg = safe_api_call(api.get_full_name)
    if success:
        pass
    else:
        pass

    # Get user profile number from device info
    success, device_info, _error_msg = safe_api_call(api.get_device_last_used)
    if success and device_info and device_info.get("userProfileNumber"):
        device_info.get("userProfileNumber")
    elif not success:
        pass
    else:
        pass


def display_daily_stats(api: Garmin):
    """Display today's activity statistics with proper error handling."""
    today = date.today().isoformat()

    # Get user summary (steps, calories, etc.)
    success, summary, _error_msg = safe_api_call(api.get_user_summary, today)
    if success and summary:
        steps = summary.get("totalSteps", 0)
        summary.get("totalDistanceMeters", 0) / 1000  # Convert to km
        summary.get("totalKilocalories", 0)
        summary.get("floorsClimbed", 0)

        # Fun motivation based on steps
        if steps < 5000 or steps > 15000:
            pass
        else:
            pass
    elif not success:
        pass
    else:
        pass

    # Get hydration data
    success, hydration, _error_msg = safe_api_call(api.get_hydration_data, today)
    if success and hydration and hydration.get("valueInML"):
        hydration_ml = int(hydration.get("valueInML", 0))
        hydration_goal = hydration.get("goalInML", 0)
        round(hydration_ml / 240, 1)  # 240ml = 1 cup

        if hydration_goal > 0:
            round((hydration_ml / hydration_goal) * 100)
    elif not success:
        pass
    else:
        pass


def main():
    """Main example demonstrating basic Garmin Connect API usage."""
    # Initialize API with authentication (will only prompt for credentials if needed)
    api = init_api()

    if not api:
        return

    # Display user information
    display_user_info(api)

    # Display daily statistics
    display_daily_stats(api)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
    except Exception:
        pass
