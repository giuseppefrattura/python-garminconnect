[![GitHub Release][releases-shield]][releases]
[![GitHub Activity][commits-shield]][commits]
[![License][license-shield]](LICENSE)
![Project Maintenance][maintenance-shield]

[![Donate via PayPal](https://img.shields.io/badge/Donate-PayPal-blue.svg?style=for-the-badge&logo=paypal)](https://www.paypal.me/cyberjunkynl/)
[![Sponsor on GitHub](https://img.shields.io/badge/Sponsor-GitHub-red.svg?style=for-the-badge&logo=github)](https://github.com/sponsors/cyberjunky)

# Python: Garmin Connect

The Garmin Connect API library comes with two examples:

- **`example.py`** - Simple getting-started example showing authentication, token storage, and basic API calls
- **`demo.py`** - Comprehensive demo providing access to **105+ API methods** organized into **12 categories** for easy navigation

Note: The demo menu is generated dynamically; exact options may change between releases.

```bash
$ ./demo.py
🏃‍♂️ Full-blown Garmin Connect API Demo - Main Menu
==================================================
Select a category:

  [1] 👤 User & Profile
  [2] 📊 Daily Health & Activity
  [3] 🔬 Advanced Health Metrics
  [4] 📈 Historical Data & Trends
  [5] 🏃 Activities & Workouts
  [6] ⚖️ Body Composition & Weight
  [7] 🏆 Goals & Achievements
  [8] ⌚ Device & Technical
  [9] 🎽 Gear & Equipment
  [0] 💧 Hydration & Wellness
  [a] 🔧 System & Export
  [b] 📅 Training plans

  [q] Exit program

Make your selection:
```

## API Coverage Statistics

- **Total API Methods**: 105+ unique endpoints (snapshot)
- **Categories**: 12 organized sections
- **User & Profile**: 4 methods (basic user info, settings)
- **Daily Health & Activity**: 9 methods (today's health data)
- **Advanced Health Metrics**: 11 methods (fitness metrics, HRV, VO2, training readiness)
- **Historical Data & Trends**: 9 methods (date range queries, weekly aggregates)
- **Activities & Workouts**: 28 methods (comprehensive activity and workout management)
- **Body Composition & Weight**: 8 methods (weight tracking, body composition)
- **Goals & Achievements**: 15 methods (challenges, badges, goals)
- **Device & Technical**: 7 methods (device info, settings)
- **Gear & Equipment**: 8 methods (gear management, tracking)
- **Hydration & Wellness**: 9 methods (hydration, blood pressure, menstrual)
- **System & Export**: 4 methods (reporting, logout, GraphQL)
- **Training Plans**: 3 methods

### Interactive Features

- **Enhanced User Experience**: Categorized navigation with emoji indicators
- **Smart Data Management**: Interactive weigh-in deletion with search capabilities
- **Comprehensive Coverage**: All major Garmin Connect features are accessible
- **Error Handling**: Robust error handling with user-friendly prompts
- **Data Export**: JSON export functionality for all data types

[![Donate via PayPal](https://img.shields.io/badge/Donate-PayPal-blue.svg?style=for-the-badge&logo=paypal)](https://www.paypal.me/cyberjunkynl/)
[![Sponsor on GitHub](https://img.shields.io/badge/Sponsor-GitHub-red.svg?style=for-the-badge&logo=github)](https://github.com/sponsors/cyberjunky)

A comprehensive Python3 API wrapper for Garmin Connect, providing access to health, fitness, and device data.

## 📖 About

This library enables developers to programmatically access Garmin Connect data including:

- **Health Metrics**: Heart rate, sleep, stress, body composition, SpO2, HRV
- **Activity Data**: Workouts, scheduled workouts, exercises, training status, performance metrics
- **Device Information**: Connected devices, settings, alarms, solar data
- **Goals & Achievements**: Personal records, badges, challenges, race predictions
- **Historical Data**: Trends, progress tracking, date range queries

Compatible with all Garmin Connect accounts. See <https://connect.garmin.com/>

## 🌐 Web Application & Dashboard Stack (Docker)

In addition to the core library and command-line examples, this repository includes a multi-service containerized web application stack managed via Docker Compose.

The stack comprises the following services:

### 1. Garmin Proxy Service (`garmin-proxy` @ port `8080`)
* Handles authentication and acts as a caching proxy for the Garmin Connect API.
* Keeps OAuth tokens active and prevents rate limits.

### 2. Garmin Fitness Analytics Dashboard (`garmin-service` @ port `8081`)
* A Spring Boot web application serving a gorgeous, interactive glassmorphic dashboard (`http://localhost:8081`).
* Visualizes your training logs, running metrics, activity trends, and custom Heart Rate Zone distributions.
* Includes a built-in integration to switch tabs and view scale weight metrics pulled directly from the Renpho service database.

![Garmin Connect Fitness Dashboard](/Users/giuseppefrattura/.gemini/antigravity-ide/brain/cdbf7798-709d-4382-a51d-9801c7883c2a/garmin_dashboard_1780741867826.png)

### 3. Renpho Scale Sync Service & Standalone Dashboard (`renpho-service` @ port `8082`)
* A FastAPI Python microservice that syncs weight and body composition data (body fat, muscle mass, BMI, bone, water, and impedance resistance) from the Renpho Health API to a local PostgreSQL instance.
* Runs a background scheduler to automatically fetch new data daily.
* Serves a standalone, fully-featured weight analytics dashboard (`http://localhost:8082`) with:
  * **Auto-Sync on Load**: Automatically checks credentials status, connects to the Renpho cloud on page load to pull the latest scale records, and populates the database and graphs.
  * **Analytics Charts**: Features interactive Chart.js visualizations including a Weight & Body Fat trend line graph and a detailed body mass composition doughnut chart.
  * **Records History**: Displays all weigh-in logs in a responsive data table with a live search box to filter results by date.

![Renpho Scale Standalone Dashboard](/Users/giuseppefrattura/.gemini/antigravity-ide/brain/cdbf7798-709d-4382-a51d-9801c7883c2a/dashboard_loaded_1780741367927.png)

## 📦 Installation

Install from PyPI:

```bash
python3 -m pip install --upgrade pip
python3 -m pip install garminconnect
```

## Run demo software (recommended)

```bash
python3 -m venv .venv --copies
source .venv/bin/activate  # On Windows: .venv\Scripts\activate
pip install pdm
pdm install --group :example

# Run the simple example
python3 ./example.py

# Run the comprehensive demo
python3 ./demo.py
```


## 🛠️ Development

Set up a development environment for contributing:

> **Note**: This project uses [PDM](https://pdm.fming.dev/) for modern Python dependency management and task automation. All development tasks are configured as PDM scripts in `pyproject.toml`. The Python interpreter is automatically configured to use `.venv/bin/python` when you create the virtual environment.

**Environment Setup:**

> **⚠️ Important**: On externally-managed Python environments (like Debian/Ubuntu), you must create a virtual environment before installing PDM to avoid system package conflicts.

```bash
# 1. Create and activate a virtual environment
python3 -m venv .venv --copies
source .venv/bin/activate  # On Windows: .venv\Scripts\activate

# 2. Install PDM (Python Dependency Manager)
pip install pdm

# 3. Install all development dependencies
pdm install --group :all

# 4. Install optional tools for enhanced development experience
pip install "black[jupyter]" codespell pre-commit

# 5. Setup pre-commit hooks (optional)
pre-commit install --install-hooks
```

**Alternative for System-wide PDM Installation:**
```bash
# Install PDM via pipx (recommended for system-wide tools)
python3 -m pip install --user pipx
pipx install pdm

# Then proceed with project setup
pdm install --group :all
```

**Available Development Commands:**
```bash
pdm run format      # Auto-format code (isort, black, ruff --fix)
pdm run lint        # Check code quality (isort, ruff, black, mypy)
pdm run codespell   # Check spelling errors (install codespell if needed)
pdm run test        # Run test suite
pdm run testcov     # Run tests with coverage report
pdm run all         # Run all checks
pdm run clean      # Clean build artifacts and cache files
pdm run build      # Build package for distribution
pdm run publish    # Build and publish to PyPI
```

**View all available commands:**
```bash
pdm run --list     # Display all available PDM scripts
```

**Code Quality Workflow:**
```bash
# Before making changes
pdm run lint       # Check current code quality

# After making changes
pdm run format     # Auto-format your code
pdm run lint       # Verify code quality
pdm run codespell  # Check spelling
pdm run test       # Run tests to ensure nothing broke
```

Run these commands before submitting PRs to ensure code quality standards.

## 🔐 Authentication

The library uses the same OAuth authentication as the official Garmin Connect app via [Garth](https://github.com/matin/garth).

**Key Features:**
- Login credentials valid for one year (no repeated logins)
- Secure OAuth token storage
- Same authentication flow as official app

**Advanced Configuration:**
```python
# Optional: Custom OAuth consumer (before login)
import os
import garth
garth.sso.OAUTH_CONSUMER = {
    'key': os.getenv('GARTH_OAUTH_KEY', '<YOUR_KEY>'),
    'secret': os.getenv('GARTH_OAUTH_SECRET', '<YOUR_SECRET>'),
}
# Note: Set these env vars securely; placeholders are non-sensitive.
```

**Token Storage:**
Tokens are automatically saved to `~/.garminconnect` directory for persistent authentication.
For security, ensure restrictive permissions:

```bash
chmod 700 ~/.garminconnect
chmod 600 ~/.garminconnect/* 2>/dev/null || true
```

## 🧪 Testing

Run the test suite to verify functionality:

**Prerequisites:**

Create tokens in ~/.garminconnect by running the example program.

```bash
# Install development dependencies
pdm install --group :all
```

**Run Tests:**

```bash
pdm run test        # Run all tests
pdm run testcov     # Run tests with coverage report
```

Optional: keep test tokens isolated

```bash
export GARMINTOKENS="$(mktemp -d)"
python3 ./example.py # create fresh tokens for tests
pdm run test
```

**Note:** Tests automatically use `~/.garminconnect` as the default token file location. You can override this by setting the `GARMINTOKENS` environment variable. Run `example.py` first to generate authentication tokens for testing.

**For Developers:** Tests use VCR cassettes to record/replay HTTP interactions. If tests fail with authentication errors, ensure valid tokens exist in `~/.garminconnect`

## 📦 Publishing

For package maintainers:

**Setup PyPI credentials:**

```bash
pip install twine
# Edit with your preferred editor, or create via here-doc:
# cat > ~/.pypirc <<'EOF'
# [pypi]
# username = __token__
# password = <PyPI_API_TOKEN>
# EOF
```

```ini
[pypi]
username = __token__
password = <PyPI_API_TOKEN>
```

Recommended: use environment variables and restrict file perms

```bash
chmod 600 ~/.pypirc
export TWINE_USERNAME="__token__"
export TWINE_PASSWORD="<PyPI_API_TOKEN>"
```

**Publish new version:**

```bash
pdm run publish    # Build and publish to PyPI
```

**Alternative publishing steps:**

```bash
pdm run build      # Build package only
pdm publish        # Publish pre-built package
```

## 🤝 Contributing

We welcome contributions! Here's how you can help:

- **Report Issues**: Bug reports and feature requests via GitHub issues
- **Submit PRs**: Code improvements, new features, documentation updates
- **Testing**: Help test new features and report compatibility issues
- **Documentation**: Improve examples, add use cases, fix typos

**Before Contributing:**
1. Set up development environment (`pdm install --group :all`)
2. Execute code quality checks (`pdm run format && pdm run lint`)
3. Test your changes (`pdm run test`)
4. Follow existing code style and patterns

**Development Workflow:**
```bash
# 1. Setup environment (with virtual environment)
python3 -m venv .venv --copies
source .venv/bin/activate
pip install pdm
pdm install --group :all

# 2. Make your changes
# ... edit code ...

# 3. Quality checks
pdm run format     # Auto-format code
pdm run lint       # Check code quality
pdm run test       # Run tests

# 4. Submit PR
git commit -m "Your changes"
git push origin your-branch
```

### Jupyter Notebook

Explore the API interactively with our [reference notebook](https://github.com/cyberjunky/python-garminconnect/blob/master/reference.ipynb).

### Python Code Examples

```python
from garminconnect import Garmin
import os

# Initialize and login
client = Garmin(
    os.getenv("GARMIN_EMAIL", "<YOUR_EMAIL>"),
    os.getenv("GARMIN_PASSWORD", "<YOUR_PASSWORD>")
)
client.login()

# Get today's stats
from datetime import date
_today = date.today().strftime('%Y-%m-%d')
stats = client.get_stats(_today)

# Get heart rate data
hr_data = client.get_heart_rates(_today)
print(f"Resting HR: {hr_data.get('restingHeartRate', 'n/a')}")
```

### Additional Resources
- **Simple Example**: [example.py](https://raw.githubusercontent.com/cyberjunky/python-garminconnect/master/example.py) - Getting started guide
- **Comprehensive Demo**: [demo.py](https://raw.githubusercontent.com/cyberjunky/python-garminconnect/master/demo.py) - All 105+ API methods
- **API Documentation**: Comprehensive method documentation in source code
- **Test Cases**: Real-world usage examples in `tests/` directory

## 🙏 Acknowledgments

Special thanks to all contributors who have helped improve this project:

- **Community Contributors**: Bug reports, feature requests, and code improvements
- **Issue Reporters**: Helping identify and resolve compatibility issues
- **Feature Developers**: Adding new API endpoints and functionality
- **Documentation Authors**: Improving examples and user guides

This project thrives thanks to community involvement and feedback.

## 💖 Support This Project

If you find this library useful for your projects, please consider supporting its continued development and maintenance:

### 🌟 Ways to Support

- **⭐ Star this repository** - Help others discover the project
- **💰 Financial Support** - Contribute to development and hosting costs
- **🐛 Report Issues** - Help improve stability and compatibility
- **📖 Spread the Word** - Share with other developers

### 💳 Financial Support Options

[![Donate via PayPal](https://img.shields.io/badge/Donate-PayPal-blue.svg?style=for-the-badge&logo=paypal)](https://www.paypal.me/cyberjunkynl/)
[![Sponsor on GitHub](https://img.shields.io/badge/Sponsor-GitHub-red.svg?style=for-the-badge&logo=github)](https://github.com/sponsors/cyberjunky)

**Why Support?**
- Keeps the project actively maintained
- Enables faster bug fixes and new features
- Supports infrastructure costs (testing, AI, CI/CD)
- Shows appreciation for hundreds of hours of development

Every contribution, no matter the size, makes a difference and is greatly appreciated! 🙏

[releases-shield]: https://img.shields.io/github/release/cyberjunky/python-garminconnect.svg?style=for-the-badge
[releases]: https://github.com/cyberjunky/python-garminconnect/releases
[commits-shield]: https://img.shields.io/github/commit-activity/y/cyberjunky/python-garminconnect.svg?style=for-the-badge
[commits]: https://github.com/cyberjunky/python-garminconnect/commits/main
[license-shield]: https://img.shields.io/github/license/cyberjunky/python-garminconnect.svg?style=for-the-badge
[maintenance-shield]: https://img.shields.io/badge/maintainer-cyberjunky-blue.svg?style=for-the-badge
