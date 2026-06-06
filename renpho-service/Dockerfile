# ---------- build stage ----------
FROM python:3.12-slim AS builder

WORKDIR /build

COPY requirements.txt .
RUN pip install --no-cache-dir --prefix=/install -r requirements.txt

# ---------- runtime stage ----------
FROM python:3.12-slim

WORKDIR /app

RUN apt-get update && \
    apt-get install -y --no-install-recommends libpq5 curl && \
    rm -rf /var/lib/apt/lists/*

COPY --from=builder /install /usr/local
COPY main.py .

RUN useradd --create-home appuser
USER appuser

EXPOSE 8082

CMD ["python", "-m", "uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8082"]
