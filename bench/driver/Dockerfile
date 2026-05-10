FROM python:3.11-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
        curl ca-certificates && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt /app/
RUN pip install --no-cache-dir -r requirements.txt

# Single image used for driver, consumer, and sidecar — entry point is set by compose.
COPY driver.py /app/driver.py
COPY consumer.py /app/consumer.py
COPY sidecar.py /app/sidecar.py
COPY schema.py /app/schema.py

ENV PYTHONUNBUFFERED=1
