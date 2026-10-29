package com.moveinsync.zones.core.catalog;

import com.moveinsync.zones.core.attribution.NightWindow;
import com.moveinsync.zones.core.attribution.Ping;
import com.moveinsync.zones.core.attribution.TripSummary;
import com.moveinsync.zones.core.attribution.TripTracker;
import com.moveinsync.zones.core.error.BadRequestException;
import com.moveinsync.zones.core.geo.GeoPoint;
import com.moveinsync.zones.core.index.ZoneSnapshot;
import com.moveinsync.zones.core.metrics.ZoneMetrics;
import com.moveinsync.zones.core.zone.ZoneVersion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Read-only questions: "which zones is this point in?" and one-shot trip attribution. */
public final class ZoneQueryService {

    public record PointResult(long catalogVersion, List<ZoneVersion> zones) {
    }

    public record BatchResult(long catalogVersion, List<List<ZoneVersion>> results) {
    }

    private final SnapshotManager snapshots;
    private final NightWindow nightWindow;
    private final ZoneMetrics metrics;
    private final int maxBatch;

    public ZoneQueryService(SnapshotManager snapshots, NightWindow nightWindow, ZoneMetrics metrics, int maxBatch) {
        this.snapshots = snapshots;
        this.nightWindow = nightWindow;
        this.metrics = metrics;
        this.maxBatch = maxBatch;
    }

    /** @param catalogVersion null = live version; otherwise an exact historic version */
    public PointResult query(String tenantId, double lat, double lng, Long catalogVersion) {
        checkPoint(lat, lng);
        ZoneSnapshot snap = snapshot(tenantId, catalogVersion);
        long start = System.nanoTime();
        List<ZoneVersion> zones = snap.query(lat, lng);
        metrics.pointQuery(System.nanoTime() - start, zones.size());
        return new PointResult(snap.catalogVersion(), zones);
    }

    /** Every point is answered from the SAME snapshot, so a batch is consistent. */
    public BatchResult queryBatch(String tenantId, List<GeoPoint> points, Long catalogVersion) {
        if (points == null || points.isEmpty() || points.size() > maxBatch) {
            throw new BadRequestException("INVALID_BATCH", "send between 1 and " + maxBatch + " points");
        }
        for (GeoPoint p : points) {
            checkPoint(p.lat(), p.lng());
        }
        ZoneSnapshot snap = snapshot(tenantId, catalogVersion);
        List<List<ZoneVersion>> results = new ArrayList<>(points.size());
        for (GeoPoint p : points) {
            long start = System.nanoTime();
            List<ZoneVersion> zones = snap.query(p.lat(), p.lng());
            metrics.pointQuery(System.nanoTime() - start, zones.size());
            results.add(zones);
        }
        return new BatchResult(snap.catalogVersion(), results);
    }

    /** Attribute a complete path in one call, using ONE zone version for everything. */
    public TripSummary attribute(String tenantId, String label, List<Ping> pings, Long catalogVersion) {
        if (pings == null || pings.size() < 1 || pings.size() > maxBatch) {
            throw new BadRequestException("INVALID_BATCH", "send between 1 and " + maxBatch + " pings");
        }
        ZoneSnapshot snap = snapshot(tenantId, catalogVersion);
        List<Ping> sorted = new ArrayList<>(pings);
        sorted.sort(Comparator.comparingLong(Ping::epochMillis));
        TripTracker tracker = new TripTracker(label, snap, () -> snap, nightWindow);
        for (Ping p : sorted) {
            tracker.feed(p);
        }
        return tracker.close();
    }

    private ZoneSnapshot snapshot(String tenantId, Long catalogVersion) {
        if (catalogVersion == null) {
            return snapshots.current(tenantId);
        }
        return snapshots.at(tenantId, catalogVersion);
    }

    private static void checkPoint(double lat, double lng) {
        if (!GeoPoint.isValid(lat, lng)) {
            throw new BadRequestException("INVALID_COORDINATE", "lat must be -90..90 and lng -180..180");
        }
    }
}
