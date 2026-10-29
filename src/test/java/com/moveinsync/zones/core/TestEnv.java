package com.moveinsync.zones.core;

import com.moveinsync.zones.core.attribution.NightWindow;
import com.moveinsync.zones.core.catalog.InMemoryZoneRepository;
import com.moveinsync.zones.core.catalog.SnapshotManager;
import com.moveinsync.zones.core.catalog.ZoneCatalogService;
import com.moveinsync.zones.core.catalog.ZoneQueryService;
import com.moveinsync.zones.core.catalog.ZoneRepository;
import com.moveinsync.zones.core.index.GridSnapshotBuilder;
import com.moveinsync.zones.core.index.SnapshotBuilder;
import com.moveinsync.zones.core.metrics.ZoneMetrics;
import com.moveinsync.zones.core.trip.TripService;
import com.moveinsync.zones.core.zone.ZoneType;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Wires the core exactly like the Spring config does, without Spring. */
public final class TestEnv implements AutoCloseable {

    public static final NightWindow NIGHT = new NightWindow(LocalTime.of(22, 0), LocalTime.of(6, 0), ZoneId.of("Asia/Kolkata"));

    /** Counts what the core reports, so tests can assert on metrics. */
    public static final class CountingMetrics implements ZoneMetrics {
        public final AtomicInteger swaps = new AtomicInteger();
        public final AtomicInteger rebuildFailures = new AtomicInteger();
        public final AtomicInteger rejected = new AtomicInteger();
        public final AtomicInteger noGoAlerts = new AtomicInteger();

        @Override
        public void indexSwapped(String tenantId, long v, long buildMillis, long stalenessMillis, int zoneCount) {
            swaps.incrementAndGet();
        }

        @Override
        public void indexRebuildFailed(String tenantId) {
            rebuildFailures.incrementAndGet();
        }

        @Override
        public void zoneRejected(String code) {
            rejected.incrementAndGet();
        }

        @Override
        public void noGoAlert(String tenantId) {
            noGoAlerts.incrementAndGet();
        }
    }

    public final ZoneRepository repo;
    public final CountingMetrics metrics = new CountingMetrics();
    public final SnapshotManager snapshots;
    public final ZoneCatalogService catalog;
    public final ZoneQueryService queries;
    public final TripService trips;

    public TestEnv() {
        this(new InMemoryZoneRepository(), new GridSnapshotBuilder(0.01, 4096));
    }

    public TestEnv(ZoneRepository repo, SnapshotBuilder builder) {
        this.repo = repo;
        this.snapshots = new SnapshotManager(repo, builder, metrics, new SnapshotManager.Settings(8, 20, 200, 2));
        this.snapshots.initialize();
        this.catalog = new ZoneCatalogService(repo, snapshots, TestShapes.VALIDATOR, metrics, Clock.systemUTC());
        this.queries = new ZoneQueryService(snapshots, NIGHT, metrics, 10_000);
        this.trips = new TripService(snapshots, NIGHT, metrics, Clock.systemUTC(), 10_000);
    }

    public static ZoneCatalogService.ZoneDraft draft(String id, ZoneType type, int priority, List<List<double[]>> coords) {
        return new ZoneCatalogService.ZoneDraft(id, id, type, priority, coords, "test");
    }

    /** Waits until the live index has caught up with every committed edit. */
    public void awaitIndex(String tenant) {
        long latest = repo.latestCatalogVersion(tenant);
        if (!snapshots.awaitVersion(tenant, latest, Duration.ofSeconds(10))) {
            throw new AssertionError("index did not reach version " + latest);
        }
    }

    @Override
    public void close() {
        snapshots.close();
    }
}
