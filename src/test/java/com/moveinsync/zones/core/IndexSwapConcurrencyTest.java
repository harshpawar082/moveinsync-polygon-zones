package com.moveinsync.zones.core;

import com.moveinsync.zones.core.catalog.ZoneQueryService;
import com.moveinsync.zones.core.geo.Polygon;
import com.moveinsync.zones.core.index.ZoneSnapshot;
import com.moveinsync.zones.core.zone.ZoneType;
import com.moveinsync.zones.core.zone.ZoneVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.moveinsync.zones.core.TestEnv.draft;
import static com.moveinsync.zones.core.TestShapes.coords;
import static com.moveinsync.zones.core.TestShapes.rect;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Readers query non-stop while an admin edits a zone 40 times. Every answer
 * must match EXACTLY the zones of the catalog version it reports (never a mix
 * of two versions or a half-built index), each reader must see versions only
 * move forward, and no reader may fail.
 */
class IndexSwapConcurrencyTest {

    private final TestEnv env = new TestEnv();

    @AfterEach
    void tearDown() {
        env.close();
    }

    private record Sample(long version, double lat, double lng, List<String> zoneIds) {
    }

    @Test
    void queriesDuringLiveEditsAlwaysSeeOneConsistentVersion() throws Exception {
        String t = "t1";
        Random rnd = new Random(1);
        // 300 filler zones so every rebuild does real work
        for (int i = 0; i < 300; i++) {
            double cx = 77.4 + rnd.nextDouble() * 0.4;
            double cy = 12.8 + rnd.nextDouble() * 0.4;
            env.catalog.create(t, "seed", draft(String.format("F%03d", i), ZoneType.TARIFF, rnd.nextInt(3),
                    TestShapes.star(rnd, cx, cy, 0.01 + rnd.nextDouble() * 0.02, 64, false)));
        }
        env.catalog.create(t, "seed", draft("MOVER", ZoneType.NO_GO, 9, coords(rect(77.50, 12.90, 77.55, 12.95))));
        env.awaitIndex(t);

        AtomicBoolean writing = new AtomicBoolean(true);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        List<List<Sample>> perReader = new ArrayList<>();
        List<Thread> readers = new ArrayList<>();
        List<long[]> latencies = new ArrayList<>();

        for (int r = 0; r < 4; r++) {
            List<Sample> samples = new ArrayList<>();
            long[] lat = new long[400_000];
            perReader.add(samples);
            latencies.add(lat);
            final int seed = r;
            Thread th = new Thread(() -> {
                Random local = new Random(100 + seed);
                int n = 0;
                long lastVersion = 0;
                try {
                    while (writing.get() && n < lat.length) {
                        double qLat = 12.88 + local.nextDouble() * 0.09;
                        double qLng = 77.48 + local.nextDouble() * 0.14;
                        long start = System.nanoTime();
                        ZoneQueryService.PointResult res = env.queries.query(t, qLat, qLng, null);
                        lat[n] = System.nanoTime() - start;
                        n++;
                        if (res.catalogVersion() < lastVersion) {
                            throw new AssertionError("version went backwards: " + lastVersion + " -> " + res.catalogVersion());
                        }
                        lastVersion = res.catalogVersion();
                        if (n % 7 == 0) {
                            List<String> ids = new ArrayList<>();
                            for (ZoneVersion z : res.zones()) {
                                ids.add(z.zoneId());
                            }
                            samples.add(new Sample(res.catalogVersion(), qLat, qLng, ids));
                        }
                    }
                } catch (Throwable e) {
                    failures.add(e);
                }
            });
            readers.add(th);
            th.start();
        }

        for (int i = 0; i < 40; i++) {
            double shift = (i % 2 == 0) ? 0.07 : 0.0;
            env.catalog.update(t, "admin", "MOVER", i + 1,
                    draft("MOVER", ZoneType.NO_GO, 9, coords(rect(77.50 + shift, 12.90, 77.55 + shift, 12.95))));
            Thread.sleep(3);
        }
        env.awaitIndex(t);
        Thread.sleep(20);
        writing.set(false);
        for (Thread th : readers) {
            th.join();
        }
        assertTrue(failures.isEmpty(), "reader failed: " + failures.peek());

        // Verify every sample against a brute-force answer for ITS version.
        Map<Long, List<ZoneVersion>> zonesByVersion = new HashMap<>();
        Set<Long> versionsSeen = new HashSet<>();
        int checked = 0;
        for (List<Sample> samples : perReader) {
            for (Sample s : samples) {
                versionsSeen.add(s.version());
                List<ZoneVersion> zones = zonesByVersion.computeIfAbsent(s.version(), v -> env.repo.activeAt(t, v));
                List<ZoneVersion> expected = new ArrayList<>();
                for (ZoneVersion z : zones) {
                    Polygon p = z.polygon();
                    if (p.contains(s.lng(), s.lat())) {
                        expected.add(z);
                    }
                }
                expected.sort(ZoneSnapshot.RESULT_ORDER);
                List<String> expectedIds = new ArrayList<>();
                for (ZoneVersion z : expected) {
                    expectedIds.add(z.zoneId());
                }
                assertEquals(expectedIds, s.zoneIds(), "version " + s.version() + " at " + s.lat() + "," + s.lng());
                checked++;
            }
        }
        assertTrue(checked > 1000, "only " + checked + " samples");
        assertTrue(versionsSeen.size() > 3, "readers saw only " + versionsSeen.size() + " versions; swaps did not overlap reads");

        long[] all = merge(latencies);
        Arrays.sort(all);
        long p99 = all[(int) (all.length * 0.99)];
        System.out.printf("    concurrency: %d queries, %d samples verified, %d versions seen, p50=%.1f us p99=%.1f us%n",
                all.length, checked, versionsSeen.size(), all[all.length / 2] / 1000.0, p99 / 1000.0);
        assertTrue(p99 < 5_000_000, "p99 during rebuilds was " + p99 / 1000 + " us");
    }

    private static long[] merge(List<long[]> arrays) {
        int total = 0;
        for (long[] a : arrays) {
            for (long v : a) {
                if (v > 0) {
                    total++;
                }
            }
        }
        long[] out = new long[total];
        int i = 0;
        for (long[] a : arrays) {
            for (long v : a) {
                if (v > 0) {
                    out[i++] = v;
                }
            }
        }
        return out;
    }
}
