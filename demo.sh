#!/usr/bin/env bash
# Walks through the main flows against a running server (docker compose up).
# Usage: ./demo.sh            (needs only curl)
set -u
BASE=${BASE:-http://localhost:8080}

step() { printf '\n\033[1;34m== %s\033[0m\n' "$1"; }
login() {
  curl -s -X POST "$BASE/api/v1/auth/token" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$2\"}" | sed -E 's/.*"accessToken":"([^"]+)".*/\1/'
}

ADMIN=$(login admin-acme admin123)
OPER=$(login operator-acme oper123)
EMP=$(login employee-acme emp123)
GLOBEX=$(login admin-globex admin123)

step "1. Which zones is this point in? (airport, acme tenant)"
curl -s "$BASE/api/v1/query/point?lat=13.2&lng=77.65" -H "Authorization: Bearer $OPER"; echo

step "2. Airport drop, 42 km: distance split by tariff zone"
curl -s -X POST "$BASE/api/v1/query/attribution" -H "Authorization: Bearer $OPER" \
  -H 'Content-Type: application/json' --data @samples/airport-trip.json; echo

step "3. Invalid polygon (bow tie) is rejected before it is published"
curl -s -X POST "$BASE/api/v1/zones" -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' --data @samples/zone-bowtie.json; echo

step "4. Live night trip through the quarry no-go zone (with a duplicate and a late ping)"
curl -s -X POST "$BASE/api/v1/trips" -H "Authorization: Bearer $OPER" \
  -H 'Content-Type: application/json' --data @samples/start-night-trip.json; echo
curl -s -X POST "$BASE/api/v1/trips/NIGHT-001/pings" -H "Authorization: Bearer $OPER" \
  -H 'Content-Type: application/json' --data @samples/night-trip-pings.json; echo
curl -s -X POST "$BASE/api/v1/trips/NIGHT-001/end" -H "Authorization: Bearer $OPER"; echo

step "5. The rider can read their own trip; tenant globex cannot see it"
curl -s -o /dev/null -w "employee-acme -> %{http_code}\n" "$BASE/api/v1/trips/NIGHT-001" -H "Authorization: Bearer $EMP"
curl -s -o /dev/null -w "admin-globex  -> %{http_code}\n" "$BASE/api/v1/trips/NIGHT-001" -H "Authorization: Bearer $GLOBEX"
curl -s "$BASE/api/v1/zones/BLR_AIRPORT" -H "Authorization: Bearer $GLOBEX"; echo

step "6. Live edit: shrink the no-go zone, then query old and new shapes"
curl -s -X PUT "$BASE/api/v1/zones/QUARRY_NOGO" -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' --data @samples/shrink-quarry.json | head -c 300; echo
curl -s "$BASE/api/v1/index/status?waitForVersion=5" -H "Authorization: Bearer $OPER"; echo
curl -s "$BASE/api/v1/query/point?lat=13.04&lng=77.65" -H "Authorization: Bearer $OPER"; echo
curl -s "$BASE/api/v1/query/point?lat=13.04&lng=77.65&catalogVersion=4" -H "Authorization: Bearer $OPER"; echo

step "7. Same edit again with the old expectedVersion -> 409 conflict"
curl -s -X PUT "$BASE/api/v1/zones/QUARRY_NOGO" -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' --data @samples/shrink-quarry.json; echo

step "8. Metrics an on-call engineer watches"
curl -s "$BASE/actuator/prometheus" | grep -E "^zones_(index_pending|index_catalog|pings_total|nogo|catalog_rejections|query_latency_seconds\{)" | head -20
