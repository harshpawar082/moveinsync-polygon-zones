package com.moveinsync.zones.bench;

import com.moveinsync.zones.core.attribution.NightWindow;
import com.moveinsync.zones.core.attribution.Ping;
import com.moveinsync.zones.core.attribution.TripTracker;
import com.moveinsync.zones.core.catalog.InMemoryZoneRepository;
import com.moveinsync.zones.core.catalog.SnapshotManager;
import com.moveinsync.zones.core.catalog.ZoneCatalogService;
import com.moveinsync.zones.core.geo.BoundingBox;
import com.moveinsync.zones.core.geo.Polygon;
import com.moveinsync.zones.core.index.BruteForceIndex;
import com.moveinsync.zones.core.index.GridIndex;
import com.moveinsync.zones.core.index.GridSnapshotBuilder;
import com.moveinsync.zones.core.index.ZoneSnapshot;
import com.moveinsync.zones.core.metrics.ZoneMetrics;
import com.moveinsync.zones.core.validation.PolygonValidator;
import com.moveinsync.zones.core.zone.ZoneType;
import com.moveinsync.zones.core.zone.ZoneVersion;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Benchmark for the case study's scale target: 5,000 polygons, 50,000 pings/s.
 *
 * Run (after mvn test-compile):
 *   java -Xmx2g -cp target/classes:target/test-classes:$(cat cp.txt) com.moveinsync.zones.bench.ZoneBenchmark
 * or simply: ./bench.sh
 */
public final class ZoneBenchmark {

    private static final double MIN_LNG = 77.35;
    private static final double MIN_LAT = 12.75;
    private static final double SPAN = 0.5; // ~55 km x 55 km, a Bengaluru-sized area

    public static void main(String[] args) throws Exception {
        int zoneCount = args.length > 0 ? Integer.parseInt(args[0]) : 5000;
        Random rnd = new Random(2026);
        Runtime rt = Runtime.getRuntime();
        System.out.println("# Polygon zones benchmark");
        System.out.printf("JVM %s, %d CPUs, max heap %d MB%n%n", System.getProperty("java.version"),
                rt.availableProcessors(), rt.maxMemory() / (1024 * 1024));

        // 1. generate + validate
        List<List<List<double[]>>> raw = new ArrayList<>();
        for (int i = 0; i < zoneCount; i++) {
            raw.add(randomZone(rnd));
        }
        PolygonValidator validator = PolygonValidator.standard(200_000);
        long t0 = System.nanoTime();
        List<ZoneVersion> zones = new ArrayList<>();
        long vertices = 0;
        int holes = 0;
        for (int i = 0; i < raw.size(); i++) {
            Polygon p = validator.validate(raw.get(i));
            vertices += p.vertexCount();
            holes += p.holes().size();
            ZoneType type = (i % 10 == 0) ? ZoneType.NO_GO : ZoneType.TARIFF;
            zones.add(new ZoneVersion("bench", String.format("Z%05d", i), 1, "zone " + i, type, i % 7, p, false,
                    1, "bench", "bench", Instant.EPOCH));
        }
        long validateMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("Zones: %d polygons, %,d vertices total (avg %.0f, max per zone 4,096), %d with holes%n",
                zoneCount, vertices, (double) vertices / zoneCount, holes);
        System.out.printf("Validation of all zones: %d ms (%.2f ms per zone)%n%n", validateMs, (double) validateMs / zoneCount);

        // 2. index build time + memory
        GridSnapshotBuilder builder = new GridSnapshotBuilder(0.01, 4096);
        long[] buildMs = new long[5];
        ZoneSnapshot snap = null;
        for (int i = 0; i < buildMs.length; i++) {
            long s = System.nanoTime();
            snap = builder.build("bench", 1, zones);
            buildMs[i] = (System.nanoTime() - s) / 1_000_000;
        }
        Arrays.sort(buildMs);
        GridIndex grid = (GridIndex) snap.index();
        long heapBefore = usedHeap();
        ZoneSnapshot extra = builder.build("bench", 1, zones);
        long heapAfter = usedHeap();
        System.out.println("| Index | value |");
        System.out.println("|---|---|");
        System.out.printf("| build time (median of 5) | %d ms |%n", buildMs[2]);
        System.out.printf("| grid | %s |%n", grid.describe());
        System.out.printf("| index memory (estimate) | %.1f MB |%n", grid.approxMemoryBytes() / 1e6);
        System.out.printf("| one extra snapshot, measured heap growth | %.1f MB |%n", Math.max(0, heapAfter - heapBefore) / 1e6);
        System.out.printf("| polygon vertices (16 B each, shared by all snapshots) | %.1f MB |%n%n", vertices * 16 / 1e6);
        if (extra.zones().size() != zones.size()) {
            throw new IllegalStateException();
        }

        // 3. correctness vs brute force
        List<BoundingBox> boxes = new ArrayList<>();
        for (ZoneVersion z : snap.zones()) {
            boxes.add(z.polygon().bbox());
        }
        ZoneSnapshot brute = new ZoneSnapshot("bench", 1, snap.zones(), new BruteForceIndex(boxes), Instant.EPOCH, 0);
        double[][] points = randomPoints(new Random(7), 2_000_000, snap.zones());
        int mismatches = 0;
        for (int i = 0; i < 50_000; i++) {
            if (!ids(brute.query(points[i][0], points[i][1])).equals(ids(snap.query(points[i][0], points[i][1])))) {
                mismatches++;
            }
        }
        System.out.printf("Correctness: 50,000 random points, grid vs brute force mismatches = %d%n%n", mismatches);

        // 4. single-thread throughput and latency
        long sink = 0;
        for (int i = 0; i < 500_000; i++) {
            sink += snap.query(points[i][0], points[i][1]).size();
        }
        long s = System.nanoTime();
        for (int i = 0; i < points.length; i++) {
            sink += snap.query(points[i][0], points[i][1]).size();
        }
        double singleQps = points.length / ((System.nanoTime() - s) / 1e9);
        long[] lat = new long[200_000];
        for (int i = 0; i < lat.length; i++) {
            long q = System.nanoTime();
            sink += snap.query(points[i][0], points[i][1]).size();
            lat[i] = System.nanoTime() - q;
        }
        Arrays.sort(lat);
        long bs = System.nanoTime();
        int bruteN = 20_000;
        for (int i = 0; i < bruteN; i++) {
            sink += brute.query(points[i][0], points[i][1]).size();
        }
        double bruteQps = bruteN / ((System.nanoTime() - bs) / 1e9);

        // 5. multi-thread throughput
        int threads = rt.availableProcessors();
        double multiQps = parallelQps(snap, points, threads, 3000);

        System.out.println("| Point queries (5,000 polygons) | value |");
        System.out.println("|---|---|");
        System.out.printf("| single thread | %,.0f queries/s |%n", singleQps);
        System.out.printf("| %d threads | %,.0f queries/s |%n", threads, multiQps);
        System.out.printf("| latency p50 / p99 / p99.9 | %.1f / %.1f / %.1f us |%n", lat[lat.length / 2] / 1000.0,
                lat[(int) (lat.length * 0.99)] / 1000.0, lat[(int) (lat.length * 0.999)] / 1000.0);
        System.out.printf("| brute force (test every bbox) | %,.0f queries/s (%.0fx slower) |%n", bruteQps, singleQps / bruteQps);
        System.out.printf("| target | 50,000 pings/s -> %.0fx headroom on one thread |%n%n", singleQps / 50_000);

        // 6. latency while the index is rebuilt live
        rebuildUnderLoad(zones, points);

        // 7. trip attribution throughput
        attribution(snap);
        if (sink == 42) {
            System.out.println();
        }
    }

    private static void rebuildUnderLoad(List<ZoneVersion> zones, double[][] points) throws Exception {
        InMemoryZoneRepository repo = new InMemoryZoneRepository();
        long cv = 0;
        for (ZoneVersion z : zones) {
            cv++;
            repo.append(new ZoneVersion("t", z.zoneId(), 1, z.name(), z.type(), z.priority(), z.polygon(), false, cv,
                    "bench", "bench", Instant.EPOCH));
        }
        final AtomicLong staleTotal = new AtomicLong();
        final AtomicLong swaps = new AtomicLong();
        ZoneMetrics metrics = new ZoneMetrics() {
            @Override
            public void indexSwapped(String tenantId, long v, long buildMillis, long stalenessMillis, int zoneCount) {
                staleTotal.addAndGet(stalenessMillis);
                swaps.incrementAndGet();
            }
        };
        SnapshotManager manager = new SnapshotManager(repo, new GridSnapshotBuilder(0.01, 4096), metrics,
                new SnapshotManager.Settings(8, 100, 1000, 1));
        manager.initialize();
        ZoneCatalogService catalog = new ZoneCatalogService(repo, manager, PolygonValidator.standard(200_000),
                ZoneMetrics.NOOP, Clock.systemUTC());

        long[] idle = readerLatencies(manager, points, 1500, null);
        AtomicBoolean editing = new AtomicBoolean(true);
        Thread editor = new Thread(() -> {
            Random r = new Random(5);
            try {
                for (int i = 0; i < 20; i++) {
                    ZoneVersion z = zones.get(r.nextInt(zones.size()));
                    ZoneVersion live = repo.latest("t", z.zoneId()).orElseThrow();
                    catalog.update("t", "bench", z.zoneId(), live.version(), new ZoneCatalogService.ZoneDraft(
                            z.zoneId(), z.name(), z.type(), z.priority(), randomZone(r), "bench edit"));
                    manager.awaitVersion("t", repo.latestCatalogVersion("t"), Duration.ofSeconds(10));
                }
            } finally {
                editing.set(false);
            }
        });
        editor.start();
        long[] during = readerLatencies(manager, points, 60_000, editing);
        editor.join();

        System.out.println("| Live edits (20 edits, each a full 5,000-zone rebuild) | idle | during rebuilds |");
        System.out.println("|---|---|---|");
        System.out.printf("| query p50 | %.1f us | %.1f us |%n", idle[idle.length / 2] / 1000.0, during[during.length / 2] / 1000.0);
        System.out.printf("| query p99 | %.1f us | %.1f us |%n", idle[(int) (idle.length * 0.99)] / 1000.0,
                during[(int) (during.length * 0.99)] / 1000.0);
        System.out.printf("| edit -> live (avg) | | %d ms |%n%n", swaps.get() == 0 ? 0 : staleTotal.get() / swaps.get());
        manager.close();
    }

    /** 2 reader threads; runs for durationMs or until the flag goes false. */
    private static long[] readerLatencies(SnapshotManager manager, double[][] points, long durationMs, AtomicBoolean flag)
            throws InterruptedException {
        int readers = 2;
        List<long[]> results = new ArrayList<>();
        List<int[]> counts = new ArrayList<>();
        List<Thread> ts = new ArrayList<>();
        long end = System.currentTimeMillis() + durationMs;
        for (int t = 0; t < readers; t++) {
            long[] lat = new long[2_000_000];
            int[] n = new int[1];
            results.add(lat);
            counts.add(n);
            final int offset = t * 100_000;
            Thread th = new Thread(() -> {
                int i = offset;
                while (n[0] < lat.length && System.currentTimeMillis() < end && (flag == null || flag.get())) {
                    double[] p = points[i % points.length];
                    long q = System.nanoTime();
                    manager.current("t").query(p[0], p[1]);
                    lat[n[0]++] = System.nanoTime() - q;
                    i++;
                }
            });
            ts.add(th);
            th.start();
        }
        for (Thread th : ts) {
            th.join();
        }
        int total = 0;
        for (int[] n : counts) {
            total += n[0];
        }
        long[] all = new long[total];
        int k = 0;
        for (int t = 0; t < readers; t++) {
            System.arraycopy(results.get(t), 0, all, k, counts.get(t)[0]);
            k += counts.get(t)[0];
        }
        Arrays.sort(all);
        return all;
    }

    private static void attribution(ZoneSnapshot snap) {
        NightWindow night = new NightWindow(LocalTime.of(22, 0), LocalTime.of(6, 0), ZoneId.of("Asia/Kolkata"));
        Random rnd = new Random(11);
        int trips = 2000;
        int pingsPerTrip = 300;
        long totalPings = 0;
        long start = System.nanoTime();
        long events = 0;
        for (int t = 0; t < trips; t++) {
            TripTracker tracker = new TripTracker("b" + t, snap, () -> snap, night);
            double lat = MIN_LAT + rnd.nextDouble() * SPAN;
            double lng = MIN_LNG + rnd.nextDouble() * SPAN;
            double heading = rnd.nextDouble() * 2 * Math.PI;
            long ts = 1_790_000_000_000L;
            for (int i = 0; i < pingsPerTrip; i++) {
                heading += (rnd.nextDouble() - 0.5) * 0.6;
                lat += Math.sin(heading) * 0.0009; // ~100 m per ping
                lng += Math.cos(heading) * 0.0009;
                ts += 10_000;
                tracker.feed(new Ping(lat, lng, ts));
                totalPings++;
            }
            events += tracker.close().events().size();
        }
        double secs = (System.nanoTime() - start) / 1e9;
        System.out.println("| Trip attribution (2,000 trips x 300 pings, ~100 m segments) | value |");
        System.out.println("|---|---|");
        System.out.printf("| throughput, one thread | %,.0f pings/s |%n", totalPings / secs);
        System.out.printf("| events emitted | %,d entry/exit/alert events |%n", events);
    }

    private static double parallelQps(ZoneSnapshot snap, double[][] points, int threads, long ms) throws InterruptedException {
        AtomicLong total = new AtomicLong();
        List<Thread> ts = new ArrayList<>();
        long end = System.currentTimeMillis() + ms;
        long start = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            final int offset = t * 50_000;
            Thread th = new Thread(() -> {
                long n = 0;
                int i = offset;
                while (System.currentTimeMillis() < end) {
                    for (int k = 0; k < 1000; k++) {
                        double[] p = points[i % points.length];
                        snap.query(p[0], p[1]);
                        i++;
                    }
                    n += 1000;
                }
                total.addAndGet(n);
            });
            ts.add(th);
            th.start();
        }
        for (Thread th : ts) {
            th.join();
        }
        return total.get() / ((System.nanoTime() - start) / 1e9);
    }

    /** Star-shaped polygons (valid by construction) of realistic, mixed sizes. */
    static List<List<double[]>> randomZone(Random rnd) {
        double cx = MIN_LNG + rnd.nextDouble() * SPAN;
        double cy = MIN_LAT + rnd.nextDouble() * SPAN;
        double u = rnd.nextDouble();
        double r;
        int n;
        if (u < 0.70) {
            r = 0.002 + rnd.nextDouble() * 0.008;
            n = 16 + rnd.nextInt(112);
        } else if (u < 0.95) {
            r = 0.01 + rnd.nextDouble() * 0.02;
            n = 128 + rnd.nextInt(896);
        } else {
            r = 0.03 + rnd.nextDouble() * 0.05;
            n = 1024 + rnd.nextInt(3072);
        }
        boolean hole = rnd.nextDouble() < 0.2;
        List<List<double[]>> rings = new ArrayList<>();
        rings.add(starRing(rnd, cx, cy, r, n, false));
        if (hole) {
            rings.add(starRing(rnd, cx, cy, r * 0.3, Math.max(8, n / 8), true));
        }
        return rings;
    }

    private static List<double[]> starRing(Random rnd, double cx, double cy, double r, int n, boolean clockwise) {
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n;
            if (clockwise) {
                a = -a;
            }
            double radius = r * (0.6 + 0.4 * rnd.nextDouble());
            pts.add(new double[]{cx + radius * Math.cos(a), cy + radius * Math.sin(a)});
        }
        pts.add(pts.get(0));
        return pts;
    }

    /** 70% uniform over the area, 30% near zone centres (so many queries hit polygons). */
    private static double[][] randomPoints(Random rnd, int n, List<ZoneVersion> zones) {
        double[][] out = new double[n][2];
        for (int i = 0; i < n; i++) {
            if (rnd.nextDouble() < 0.7) {
                out[i][0] = MIN_LAT + rnd.nextDouble() * SPAN;
                out[i][1] = MIN_LNG + rnd.nextDouble() * SPAN;
            } else {
                BoundingBox b = zones.get(rnd.nextInt(zones.size())).polygon().bbox();
                out[i][0] = b.minY() + rnd.nextDouble() * (b.maxY() - b.minY());
                out[i][1] = b.minX() + rnd.nextDouble() * (b.maxX() - b.minX());
            }
        }
        return out;
    }

    private static List<String> ids(List<ZoneVersion> zones) {
        List<String> out = new ArrayList<>();
        for (ZoneVersion z : zones) {
            out.add(z.zoneId());
        }
        return out;
    }

    private static long usedHeap() throws InterruptedException {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(100);
        }
        return rt.totalMemory() - rt.freeMemory();
    }
}
