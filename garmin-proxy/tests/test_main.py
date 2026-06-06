"""
Tests for garmin-proxy authentication, re-authentication, and endpoint logic.

Uses unittest.mock to patch the Garmin class and simulate token expiry.
Run with:  pytest garmin-proxy/tests/test_main.py -v
"""

from unittest.mock import MagicMock, patch

import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient
from garminconnect import GarminConnectAuthenticationError, GarminConnectConnectionError
from garth.exc import GarthHTTPError

# Import module-level symbols we need to test / reset
import garmin_proxy_main as mod


@pytest.fixture(autouse=True)
def reset_global_api():
    """Reset the global _garmin_api before each test."""
    mod._garmin_api = None
    yield
    mod._garmin_api = None


# ──────────────────────────────────────────────────────────────────────────────
# _authenticate
# ──────────────────────────────────────────────────────────────────────────────
class TestAuthenticate:

    @patch.object(mod, "Garmin")
    def test_caches_api_instance(self, MockGarmin):
        """Second call returns cached instance without calling login again."""
        mock_api = MagicMock()
        MockGarmin.return_value = mock_api

        result1 = mod._authenticate()
        result2 = mod._authenticate()

        assert result1 is mock_api
        assert result2 is mock_api
        mock_api.login.assert_called_once()  # only the first call triggers login

    @patch.object(mod, "Garmin")
    def test_force_discards_cache_and_re_authenticates(self, MockGarmin):
        """force=True discards cached instance and creates a new one."""
        old_api = MagicMock()
        new_api = MagicMock()
        MockGarmin.side_effect = [old_api, new_api]

        first = mod._authenticate()
        assert first is old_api

        second = mod._authenticate(force=True)
        assert second is new_api
        assert new_api.login.call_count == 1

    @patch.object(mod, "Garmin")
    def test_raises_runtime_error_on_auth_failure(self, MockGarmin):
        """Authentication failure wraps into RuntimeError."""
        mock_api = MagicMock()
        mock_api.login.side_effect = GarthHTTPError("401 Unauthorized", MagicMock())
        MockGarmin.return_value = mock_api

        with pytest.raises(RuntimeError, match="Garmin authentication failed"):
            mod._authenticate()


# ──────────────────────────────────────────────────────────────────────────────
# _call_with_reauth
# ──────────────────────────────────────────────────────────────────────────────
class TestCallWithReauth:

    @patch.object(mod, "Garmin")
    def test_happy_path_no_reauth(self, MockGarmin):
        """Normal call returns result without re-auth."""
        mock_api = MagicMock()
        MockGarmin.return_value = mock_api

        mod._authenticate()
        result = mod._call_with_reauth(lambda api: api.get_activities(0, 10))

        mock_api.get_activities.assert_called_once_with(0, 10)

    @patch.object(mod, "Garmin")
    def test_reauth_on_garth_http_error(self, MockGarmin):
        """GarthHTTPError triggers re-auth and retries the call."""
        old_api = MagicMock()
        new_api = MagicMock()
        MockGarmin.side_effect = [old_api, new_api]

        # First auth succeeds
        mod._authenticate()

        # First call fails with expired token, second succeeds after re-auth
        call_count = 0

        def fn(api):
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                raise GarthHTTPError("Token expired", MagicMock())
            return {"activities": []}

        result = mod._call_with_reauth(fn)

        assert result == {"activities": []}
        assert call_count == 2
        # Verify re-authentication happened (new Garmin instance created)
        assert MockGarmin.call_count == 2

    @patch.object(mod, "Garmin")
    def test_reauth_on_garmin_connect_auth_error(self, MockGarmin):
        """GarminConnectAuthenticationError also triggers re-auth."""
        old_api = MagicMock()
        new_api = MagicMock()
        MockGarmin.side_effect = [old_api, new_api]

        mod._authenticate()

        call_count = 0

        def fn(api):
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                raise GarminConnectAuthenticationError("Session expired")
            return "ok"

        result = mod._call_with_reauth(fn)
        assert result == "ok"
        assert call_count == 2

    @patch.object(mod, "Garmin")
    def test_http_503_when_reauth_fails(self, MockGarmin):
        """If re-auth also fails, raises HTTPException 503."""
        first_api = MagicMock()
        # First Garmin() succeeds, second fails on login
        failing_api = MagicMock()
        failing_api.login.side_effect = GarthHTTPError("Still unauthorized", MagicMock())
        MockGarmin.side_effect = [first_api, failing_api]

        mod._authenticate()

        def fn(api):
            raise GarthHTTPError("Token expired", MagicMock())

        with pytest.raises(HTTPException) as exc_info:
            mod._call_with_reauth(fn)

        assert exc_info.value.status_code == 503

    @patch.object(mod, "Garmin")
    def test_http_503_on_connection_error_reauth_fails(self, MockGarmin):
        """GarminConnectConnectionError + failed re-auth → 503."""
        first_api = MagicMock()
        failing_api = MagicMock()
        failing_api.login.side_effect = GarminConnectConnectionError("No network")
        MockGarmin.side_effect = [first_api, failing_api]

        mod._authenticate()

        def fn(api):
            raise GarminConnectConnectionError("Connection lost")

        with pytest.raises(HTTPException) as exc_info:
            mod._call_with_reauth(fn)

        assert exc_info.value.status_code == 503


# ──────────────────────────────────────────────────────────────────────────────
# Endpoint integration tests (via TestClient)
# ──────────────────────────────────────────────────────────────────────────────
class TestEndpoints:

    @patch.object(mod, "Garmin")
    def test_health_shows_auth_status(self, MockGarmin):
        mock_api = MagicMock()
        MockGarmin.return_value = mock_api
        mod._authenticate()

        client = TestClient(mod.app, raise_server_exceptions=False)
        resp = client.get("/health")

        assert resp.status_code == 200
        assert resp.json()["garmin_authenticated"] is True

    def test_health_unauthenticated(self):
        client = TestClient(mod.app, raise_server_exceptions=False)
        resp = client.get("/health")

        assert resp.status_code == 200
        assert resp.json()["garmin_authenticated"] is False

    @patch.object(mod, "Garmin")
    def test_get_activities_returns_data(self, MockGarmin):
        mock_api = MagicMock()
        mock_api.get_activities.return_value = [{"activityId": 1}]
        MockGarmin.return_value = mock_api
        mod._authenticate()

        client = TestClient(mod.app, raise_server_exceptions=False)
        resp = client.get("/api/activities?start=0&limit=5")

        assert resp.status_code == 200
        assert resp.json() == [{"activityId": 1}]
        mock_api.get_activities.assert_called_once_with(0, 5)

    @patch.object(mod, "Garmin")
    def test_get_activities_reauths_on_token_expiry(self, MockGarmin):
        """Full integration: endpoint re-authenticates on GarthHTTPError."""
        old_api = MagicMock()
        old_api.get_activities.side_effect = GarthHTTPError("Expired", MagicMock())

        new_api = MagicMock()
        new_api.get_activities.return_value = [{"activityId": 99}]

        MockGarmin.side_effect = [old_api, new_api]
        mod._authenticate()

        client = TestClient(mod.app, raise_server_exceptions=False)
        resp = client.get("/api/activities?start=0&limit=1")

        assert resp.status_code == 200
        assert resp.json() == [{"activityId": 99}]

    @patch.object(mod, "Garmin")
    def test_get_hr_zones_returns_data(self, MockGarmin):
        mock_api = MagicMock()
        mock_api.get_activity_hr_in_timezones.return_value = [
            {"zoneNumber": 1, "secsInZone": 600}
        ]
        MockGarmin.return_value = mock_api
        mod._authenticate()

        client = TestClient(mod.app, raise_server_exceptions=False)
        resp = client.get("/api/activities/123/hr-zones")

        assert resp.status_code == 200
        assert resp.json()[0]["zoneNumber"] == 1

    @patch.object(mod, "Garmin")
    def test_get_exercise_sets_returns_data(self, MockGarmin):
        mock_api = MagicMock()
        mock_api.get_activity_exercise_sets.return_value = {"sets": []}
        MockGarmin.return_value = mock_api
        mod._authenticate()

        client = TestClient(mod.app, raise_server_exceptions=False)
        resp = client.get("/api/activities/456/exercise-sets")

        assert resp.status_code == 200
        assert resp.json() == {"sets": []}

    @patch.object(mod, "Garmin")
    @patch.dict(mod.os.environ, {"GARMIN_API_KEY": "test-secret-key"})
    def test_api_endpoints_enforce_security(self, MockGarmin):
        """Endpoints are protected by X-API-Key when GARMIN_API_KEY is configured."""
        client = TestClient(mod.app, raise_server_exceptions=False)
        
        # 1. Without header -> 401 Unauthorized
        resp = client.get("/api/activities")
        assert resp.status_code == 401
        assert "Invalid or missing API Key" in resp.json()["detail"]

        # 2. With invalid header -> 401 Unauthorized
        resp = client.get("/api/activities", headers={"X-API-Key": "wrong-key"})
        assert resp.status_code == 401

        # 3. With correct header -> 200 OK (calls Garmin mock)
        mock_api = MagicMock()
        mock_api.get_activities.return_value = [{"activityId": 1}]
        MockGarmin.return_value = mock_api
        mod._authenticate()
        
        resp = client.get("/api/activities", headers={"X-API-Key": "test-secret-key"})
        assert resp.status_code == 200
        assert resp.json() == [{"activityId": 1}]

    @patch.object(mod, "Garmin")
    def test_caching_and_bypass(self, MockGarmin):
        """Endpoints serve cached values, which can be bypassed via query params."""
        mock_api = MagicMock()
        # Returns distinct values to differentiate live vs cache
        mock_api.get_activities.side_effect = [
            [{"activityId": 100}],
            [{"activityId": 200}]
        ]
        MockGarmin.return_value = mock_api
        mod._authenticate()
        
        client = TestClient(mod.app, raise_server_exceptions=False)
        mod._cache.clear()

        # First request (fetches live, writes to cache)
        resp1 = client.get("/api/activities?start=0&limit=10")
        assert resp1.status_code == 200
        assert resp1.json() == [{"activityId": 100}]
        assert mock_api.get_activities.call_count == 1

        # Second request (returns cached value)
        resp2 = client.get("/api/activities?start=0&limit=10")
        assert resp2.status_code == 200
        assert resp2.json() == [{"activityId": 100}]
        assert mock_api.get_activities.call_count == 1  # call count did not increase!

        # Third request with bypass_cache=True (bypasses cache, fetches live again)
        resp3 = client.get("/api/activities?start=0&limit=10&bypass_cache=true")
        assert resp3.status_code == 200
        assert resp3.json() == [{"activityId": 200}]
        assert mock_api.get_activities.call_count == 2
