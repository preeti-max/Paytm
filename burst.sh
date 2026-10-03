#!/usr/bin/env bash
set -e

BASE_URL="${1:-http://localhost:8080}"

echo "Starting Seat Reservation Burst Test against ${BASE_URL}..."
python3 "$(dirname "$0")/burst/burst.py" "${BASE_URL}"
