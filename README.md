# Polygon Zones at Scale

MoveInSync backend case study 7: tariff zones, no-go areas and trip attribution.

The service answers "which zones is this point in?" for a live stream of GPS pings. It splits every trip's kilometres by tariff zone, cutting each segment exactly where it crosses a boundary, and emits zone entry/exit events with dwell time. It raises an alert when a cab enters a no-go zone at night. Admins can edit zones while the system is running, with no downtime and no inconsistent reads.

- **Stack:** Java 17, Spring Boot 3.4 (web, security with JWT, actuator), Micrometer + Prometheus. No database: zones are kept in an append-only journal file.
- **Core logic** is in plain Java classes (`core/`), with no Spring inside. It is tested with plain unit tests.
- **Design note** with an architecture diagram, a sequence diagram and a failure-mode table: [DESIGN.md](DESIGN.md).

## Run it (one command)

```bash
docker compose up --build        # API on :8080, Prometheus on :9090
./demo.sh                        # walks through every main flow with curl
```

To run without Docker (needs Java 17+ and Maven): `mvn spring-boot:run`. To run the tests: `mvn verify`.

On first start the service loads demo zones around Bengaluru: city tariff zone (non-convex, with a hole), airport zone, city limits, and a night no-go zone. It also loads a second tenant (`globex`) for the isolation checks. `demo.sh` expects fresh data, so run `docker compose down -v` to reset.

| User | Password | Tenant | Role |
|---|---|---|---|
| admin-acme | admin123 | acme | ADMIN |
| operator-acme | oper123 | acme | OPERATOR |
| employee-acme | emp123 | acme | EMPLOYEE |
| admin-globex | admin123 | globex | ADMIN |

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/token -H 'Content-Type: application/json' \
  -d '{"username":"operator-acme","password":"oper123"}' | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')
curl -s "localhost:8080/api/v1/query/point?lat=13.2&lng=77.65" -H "Authorization: Bearer $TOKEN"
curl -s -X POST localhost:8080/api/v1/query/attribution -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' --data @samples/airport-trip.json
```

The second call reproduces the case-study example: a 42 km airport drop billed as **30.400 km BLR_CITY + 11.600 km BLR_AIRPORT**, split exactly at the toll-plaza boundary.

## API

All endpoints except login and health need `Authorization: Bearer <token>`. The tenant always comes from the token, never from the request.

| Method | Path | Role | What it does |
|---|---|---|---|
| POST | `/api/v1/auth/token` | anyone | username + password → JWT (1 h) |
| POST | `/api/v1/zones` | ADMIN | create a zone (GeoJSON Polygon, may have holes) |
| PUT | `/api/v1/zones/{id}` | ADMIN | new version of a zone; body carries `expectedVersion` |
| DELETE | `/api/v1/zones/{id}?expectedVersion=N` | ADMIN | tombstone version (history kept) |
| GET | `/api/v1/zones`, `/{id}`, `/{id}/versions`, `/{id}/versions/{v}` | ADMIN, OPERATOR | current zones and every old version |
| GET | `/api/v1/query/point?lat&lng[&catalogVersion]` | ADMIN, OPERATOR | zones containing a point, live or at an old version |
| POST | `/api/v1/query/points` | ADMIN, OPERATOR | up to 10,000 points, all answered from one snapshot |
| POST | `/api/v1/query/attribution` | ADMIN, OPERATOR | km per tariff zone + events for a full path |
| POST | `/api/v1/trips`, `/{id}/pings`, `/{id}/end` | ADMIN, OPERATOR | live trip: start, stream pings, close |
| GET | `/api/v1/trips/{id}` | all (EMPLOYEE: own trips only) | running or final split + events |
| GET | `/api/v1/index/status[?waitForVersion=N]` | ADMIN, OPERATOR | live catalog version, pending edits, last build time |
| GET | `/actuator/health`, `/actuator/prometheus` | anyone | health and metrics |

Errors always come back in one shape: `{"code":"VERSION_CONFLICT","message":"...","details":[...],"requestId":"..."}`.

## How it works

1. **Validation, before anything is published.** Six rules run in order: coordinates in range, rings closed, at least 3 vertices, no self-intersection or ring contact (sort-and-sweep), holes inside the outer ring and not nested, non-zero area. A bad polygon gets `400 INVALID_POLYGON` with every reason.
2. **Point-in-polygon.** Ray casting with a half-open vertex rule. There is an explicit "on boundary" check first, with a tolerance of 1e-9° (~0.1 mm). **Boundary rule:** a zone is a closed set, so a point on an edge, on a vertex, or on a hole's edge is inside; a point strictly inside a hole is outside. Rings with 32+ vertices get a **band index**: edges are grouped into horizontal bands, so a query only tests the few edges near its latitude instead of all of them.
3. **Spatial index.** A uniform grid of 0.01° (~1.1 km) cells. Each zone is registered in the cells its bounding box covers, so a ping looks up one cell and tests only the zones there. I chose a grid over an R-tree because pings are spread over a city, zones are similar in size, the lookup is one hash access, and a rebuild is a simple loop that takes ~20 ms for 5,000 zones. Zones covering more than 4,096 cells go to an "oversized" list.
4. **Trip attribution.** For each segment between two pings, collect every point where it crosses a candidate zone's edge, sort those points, and classify each piece by its midpoint. Every metre is billed to exactly one billable zone (highest priority, tie broken by zone id); distance outside all zones is `UNZONED`. Entry and exit times are interpolated along the segment, and EXIT events carry dwell time.
5. **No-go alerts.** When a cab enters a `NO_GO` zone and the entry time falls inside the night window (22:00–06:00 Asia/Kolkata, configurable), the service emits a `NO_GO_ALERT` event.
6. **Versioning.** Every edit appends a new immutable version with a per-tenant **catalog version**. Nothing is overwritten, so any old zone version, or the whole catalog at any old version, can still be queried.
7. **Live edits.** An edit is written to the journal (fsync) and a background thread builds a new immutable snapshot (zones + index). One `AtomicReference.compareAndSet` then swaps it in. Readers take the current snapshot once per request and never lock, so they see the old index or the new one, never a half-built one. Versions only move forward. Bursts of edits are coalesced into one rebuild.

### Trip consistency (zone edited while a trip is running)

- **Tariff, airport and city zones are pinned to the catalog version that was live when the trip started.** A running fare never changes under the driver, and the invoice can always be recomputed exactly (`billingCatalogVersion` is in the trip summary).
- **No-go zones always use the latest version.** A safety rule has to apply the moment an admin publishes it. In the case-study example (no-go zone shrunk at 9:00 PM, trip started at 8:50 PM), pings after the swap are checked against the new, smaller shape. Test: `TripVersionPinningTest`.

### Projection and error

Distances use haversine on a sphere (R = 6,371,008.8 m). Polygon edges and trip segments are treated as straight lines in lat/lng, which is how GeoJSON stores them. At Bengaluru's latitude, a 10 km edge deviates at most ~0.45 m from the great circle (L²·tanφ/8R), and a 300 m GPS segment deviates by less than 1 mm. Both are far below GPS error (3–10 m). The sphere-vs-ellipsoid difference is up to 0.3% of a distance, but it is the same on every run, so results stay reproducible. Not supported: polygons crossing the antimeridian or containing a pole.

### Rounding

All maths is done in double metres and rounded only once, at the end, to whole metres (0.001 km). The per-zone parts use the largest-remainder method, so they always add up exactly to the total. The same input always gives the same kilometres (`sameInputAlwaysGivesTheSameKilometres`).

## Benchmark

Machine: Linux cloud container, **2 vCPU**, 7 GB RAM, OpenJDK 21, `-Xmx2g`. Reproduce with `./bench.sh` (CI also runs it on every push).

Data: **5,000 polygons** spread over a 55 × 55 km Bengaluru-sized area: wavy, non-convex outlines; 85% small (150–800 m), 13% medium, 2% large with 1,024–4,096 vertices; 941 have holes; 962,542 vertices in total.

| Point queries | Result |
|---|---|
| one thread | **280,120 queries/s** (5.6× the 50,000 pings/s target) |
| two threads | **629,915 queries/s** |
| latency p50 / p99 / p99.9 | **3.2 / 10.0 / 52.9 µs** |
| testing every bounding box instead (baseline) | 20,789 queries/s, 13× slower |
| grid vs brute force on 50,000 random points | 0 mismatches |

| Index | Result |
|---|---|
| build time for 5,000 zones | 18 ms |
| index memory | 0.3 MB (2,786 cells, 24,961 registrations); one extra snapshot ≈ 0.6 MB heap |
| polygon data (shared by every snapshot) | 15.4 MB |
| validation | 0.17 ms per zone on average |

| Live edits (20 edits, each a full rebuild of 5,000 zones) | idle | during rebuilds |
|---|---|---|
| query p50 | 3.8 µs | 3.7 µs |
| query p99 | 12.1 µs | **12.8 µs** (no spike) |
| edit → live, average | | **20 ms** |

| Trip attribution | Result |
|---|---|
| 2,000 trips × 300 pings, ~100 m segments, one thread | **135,997 pings/s** (segment splitting + events) |

**Stress case, where it stops scaling.** With a deliberately bad dataset (jagged random-radius shapes with 2,500 vertices on average, and every point inside ~10 large polygons), throughput drops to ~24,000 queries/s per thread (~48,000 on 2 threads). The cost moves into the exact point-in-polygon test of the big, overlapping polygons. The next step would be to classify grid cells as fully-inside, fully-outside or boundary, so most pings skip the exact test.

## Tests: every hard rule has one

`mvn verify` runs all of these.

| Rule | Test |
|---|---|
| Non-convex shapes, holes, points on edges/vertices/inside holes, tolerance | `PointInPolygonTest` (incl. band index vs naive check on 100k points) |
| Reject not closed / self-intersecting / hole outside / nested holes / spikes | `PolygonValidatorTest` |
| Grid index gives exactly the brute-force answers | `GridIndexTest` (50,000 random points) |
| 42 km = 30.4 city + 11.6 airport, split at the boundary | `TripAttributionTest`, `DemoScenarioTest`, `ZoneApiFlowTest` |
| Entry/exit events with dwell, no-go alert only at night | `TripAttributionTest`, `DemoScenarioTest` |
| Versioning, old versions queryable, optimistic locking | `ZoneCatalogServiceTest`, `ZoneApiFlowTest` |
| **Concurrency:** queries during 40 live edits always match one version exactly, versions never go back | `IndexSwapConcurrencyTest` |
| **Failure injection:** rebuild fails → old index keeps serving → retries recover | `FailureInjectionTest` |
| **Crash:** torn journal write cut off on restart; mid-file corruption refuses to start | `FailureInjectionTest` |
| Trip pinned to its start version; shrunk no-go applies immediately | `TripVersionPinningTest` |
| Duplicate, late, shuffled pings change nothing (idempotent replay) | `TripAttributionTest`, `TripVersionPinningTest` |
| Auth, roles, tenant isolation (other tenant's zone/trip → 404) | `SecurityAndTenantIsolationTest` |
| Errors never leak internals | `SecurityAndTenantIsolationTest.errorsNeverLeakInternals` |

## Monitoring

Metrics are at `/actuator/prometheus` (Prometheus scrapes them in docker compose). Logs are one JSON object per line, and every request carries a `requestId` that is also returned in the `X-Request-Id` header.

| Metric | Alert when |
|---|---|
| `zones_index_pending_versions` | > 0 for more than 60 s: edits are not going live |
| `zones_index_rebuild_failures_total`, `zones_index_failing_tenants` | any increase |
| `zones_index_staleness_seconds` | edit-to-live time, p99 above a few seconds |
| `zones_query_latency_seconds{quantile="0.99"}` | above 1 ms |
| `zones_pings_total{outcome=duplicate,out_of_order,invalid}` | sudden jump (device or pipeline trouble) |
| `zones_nogo_alerts_total` | routed to the security desk |
| `zones_catalog_rejections_total{code}` | bad polygons being submitted |
| `zones_trips_active`, `http_server_requests_seconds` | capacity and error rate |

## Caching: what can be stale, and for how long

- **The live index** trails the latest committed edit by the rebuild time (edit-to-live averaged 20 ms in the benchmark with 5,000 zones; exported as `zones_index_staleness_seconds`). A caller who must see their own edit uses `waitForVersion`. If rebuilds keep failing, staleness grows until one succeeds. That is visible in `zones_index_pending_versions`, and the old index is still a correct answer for its version.
- **Old snapshots** (for `catalogVersion=N` queries) sit in an LRU cache of 32 entries. Snapshots are immutable, so a cached one can never be stale. Eviction only costs a rebuild the next time that version is asked for. A running trip holds its pinned snapshot directly, so it is never evicted while the trip runs.

## Trade-offs

- **Availability over freshness for reads:** if a rebuild fails, queries keep being answered from the last good version instead of failing.
- **Consistency for writes:** one writer per tenant (lock), optimistic version checks, and an edit is acknowledged only after fsync. If the disk fails, edits are refused with 503; they are never half-applied.
- **Latency:** the read path has no locks and no network calls (in-memory snapshot), so p99 is ~10 µs.
- **Full rebuild per edit** instead of incremental updates: simpler and obviously correct, and fast enough at this scale (~20 ms). Incremental copy-on-write would be the next step for catalogs 10x larger.
- **Journal file instead of a database:** gives version history for free, crash recovery, and a one-command run. The `ZoneRepository` interface lets Postgres/PostGIS replace it.

**Deliberately left out:** persisting live trip state (it is in memory; a restart loses in-progress trips, so production would use Redis or recompute from stored pings), multi-node replication, antimeridian/geodesic edges, polygon simplification, login rate limiting and refresh tokens (a real identity provider would issue the JWTs), per-tenant night windows, and streaming events to Kafka.

## Assumptions

- Coordinates are GeoJSON order `[lng, lat]`; pings carry device timestamps.
- A no-go alert fires when the **entry** into the zone happens inside the night window.
- City-limit zones are informational (not billed); TARIFF and AIRPORT zones are billed; overlaps go to the higher priority.
- Pings older than the last accepted ping of a trip are dropped as late; same-timestamp pings are duplicates.

## Project layout

```
src/main/java/com/moveinsync/zones/
  core/geo          Ring, Polygon, point-in-polygon, band index, haversine
  core/validation   PolygonRule + 6 rules, PolygonValidator
  core/index        SpatialIndex (GridIndex, BruteForceIndex), ZoneSnapshot
  core/attribution  SegmentSplitter, TripTracker, events, rounding, night window
  core/catalog      ZoneRepository (in-memory, journal), SnapshotManager, catalog + query services
  core/trip         TripService (live trips)
  api, security, observability, config, seed   Spring layer
src/test/java/...   unit, concurrency, failure, API tests; bench/ZoneBenchmark
```
