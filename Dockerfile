# ---------- build stage ----------
FROM python:3.13-slim AS builder

WORKDIR /build

# Install build deps (psycopg2-binary needs libpq at build time on slim)
RUN apt-get update && \
    apt-get install -y --no-install-recommends libpq-dev gcc && \
    rm -rf /var/lib/apt/lists/*

# Install dependencies from PyPI (garminconnect from pip, not local source)
COPY requirements.txt .
RUN pip install --no-cache-dir --prefix=/install -r requirements.txt

# ---------- runtime stage ----------
FROM python:3.13-slim

WORKDIR /app

# Runtime dependency for psycopg2
RUN apt-get update && \
    apt-get install -y --no-install-recommends libpq5 && \
    rm -rf /var/lib/apt/lists/*

# Copy installed packages from builder
COPY --from=builder /install /usr/local

# Copy the API server
COPY api_server.py .

# Create a non-root user
RUN useradd --create-home appuser
USER appuser

EXPOSE 8080

CMD ["python", "-m", "uvicorn", "api_server:app", "--host", "0.0.0.0", "--port", "8080"]
