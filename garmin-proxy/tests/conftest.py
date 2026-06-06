"""
conftest.py — make garmin-proxy/main.py importable as `garmin_proxy_main`.
"""

import importlib
import sys
from pathlib import Path

# Add parent dir to sys.path so `main.py` is importable
proxy_dir = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(proxy_dir))

# Import main.py under a test-friendly name
import main as garmin_proxy_main  # noqa: E402

sys.modules["garmin_proxy_main"] = garmin_proxy_main
