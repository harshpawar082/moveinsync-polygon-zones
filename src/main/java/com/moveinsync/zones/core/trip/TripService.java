package com.moveinsync.zones.core.trip;

import com.moveinsync.zones.core.attribution.NightWindow;
import com.moveinsync.zones.core.attribution.Ping;
import com.moveinsync.zones.core.attribution.TripSummary;
import com.moveinsync.zones.core.attribution.TripTracker;
import com.moveinsync.zones.core.attribution.ZoneEvent;
import com.moveinsync.zones.core.catalog.SnapshotManager;
import com.moveinsync.zones.core.error.BadRequestException;
import com.moveinsync.zones.core.error.ConflictException;
import com.moveinsync.zones.core.error.NotFoundException;
import com.moveinsync.zones.core.index.ZoneSnapshot;
import com.moveinsync.zones.core.metrics.ZoneMetrics;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Live trips fed by the GPS ping stream.
 *
 * - Pings of ONE trip are processed one batch at a time (a lock per trip), so
 *   they are applied in order; different trips run in parallel.
 * - Inside a batch pings are sorted by time; across batches a ping older than
 *   the last accepted one is dropped (late/out of order) and an exact repeat is
 *   a duplicate. Replaying a batch therefore changes nothing (idempotent).
 * - A trip pins the zone catalog version that was live when it started (for
 *   billing); no-go zones always use the live version. See TripTracker.
 */
public final class TripService {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");

    public record TripInfo(String tenantId, String tripId, String cabId, Set<String> riderIds,
                           long billingCatalogVersion, Instant startedAt) {
    }

    public record IngestResult(int accepted, int duplicates, int outOfOrder, int invalid, List<ZoneEvent> events) {
    }

    private static final class TripRecord {
        final TripInfo info;
        final TripTracker tracker;
        final ReentrantLock lock = new ReentrantLock();

        TripRecord(TripInfo info, TripTracker tracker) {
            this.info = info;
            this.tracker = tracker;
        }
    }

    private final SnapshotManager snapshots;
    private final NightWindow nightWindow;
    private final ZoneMetrics metrics;
    private final Clock clock;
    private final int maxPingsPerBatch;
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, TripRecord>> trips = new ConcurrentHashMap<>();

    public TripService(SnapshotManager snapshots, NightWindow nightWindow, ZoneMetrics metrics, Clock clock,
                       int maxPingsPerBatch) {
        this.snapshots = snapshots;
        this.nightWindow = nightWindow;
        this.metrics = metrics;
        this.clock = clock;
        this.maxPingsPerBatch = maxPingsPerBatch;
    }

    /** Starting the same trip twice with the same cab is a no-op (safe to retry). */
    public TripInfo start(String tenantId, String tripId, String cabId, Set<String> riderIds) {
        checkId("tripId", tripId);
        checkId("cabId", cabId);
        if (riderIds == null || riderIds.size() > 20) {
            throw new BadRequestException("INVALID_TRIP", "riderIds: 0 to 20 employee ids");
        }
        for (String rider : riderIds) {
            checkId("riderId", rider);
        }
        ConcurrentHashMap<String, TripRecord> tenantTrips = trips.computeIfAbsent(tenantId, t -> new ConcurrentHashMap<>());
        TripRecord created = tenantTrips.computeIfAbsent(tripId, id -> {
            ZoneSnapshot pinned = snapshots.current(tenantId);
            TripInfo info = new TripInfo(tenantId, tripId, cabId, Set.copyOf(riderIds), pinned.catalogVersion(),
                    clock.instant());
            TripTracker tracker = new TripTracker(tripId, pinned, () -> snapshots.current(tenantId), nightWindow);
            return new TripRecord(info, tracker);
        });
        if (!created.info.cabId().equals(cabId)) {
            throw new ConflictException("TRIP_EXISTS", "trip " + tripId + " already exists with another cab");
        }
        return created.info;
    }

    public IngestResult ingest(String tenantId, String tripId, List<Ping> pings) {
        if (pings == null || pings.isEmpty() || pings.size() > maxPingsPerBatch) {
            throw new BadRequestException("INVALID_BATCH", "send between 1 and " + maxPingsPerBatch + " pings");
        }
        TripRecord trip = require(tenantId, tripId);
        List<Ping> sorted = new ArrayList<>(pings);
        sorted.sort(Comparator.comparingLong(Ping::epochMillis));

        int accepted = 0;
        int duplicates = 0;
        int outOfOrder = 0;
        int invalid = 0;
        List<ZoneEvent> events = new ArrayList<>();
        trip.lock.lock();
        try {
            if (trip.tracker.isClosed()) {
                throw new ConflictException("TRIP_CLOSED", "trip " + tripId + " has ended");
            }
            for (Ping p : sorted) {
                TripTracker.FeedResult r = trip.tracker.feed(p);
                switch (r.outcome()) {
                    case ACCEPTED -> accepted++;
                    case DUPLICATE -> duplicates++;
                    case OUT_OF_ORDER -> outOfOrder++;
                    case INVALID -> invalid++;
                }
                events.addAll(r.events());
            }
        } finally {
            trip.lock.unlock();
        }
        metrics.pings(accepted, duplicates, outOfOrder, invalid);
        for (ZoneEvent e : events) {
            if (e.type() == ZoneEvent.Type.NO_GO_ALERT) {
                metrics.noGoAlert(tenantId);
            }
        }
        return new IngestResult(accepted, duplicates, outOfOrder, invalid, events);
    }

    /** Ends the trip and returns the final, rounded split. Calling it again returns the same summary. */
    public TripSummary end(String tenantId, String tripId) {
        TripRecord trip = require(tenantId, tripId);
        trip.lock.lock();
        try {
            return trip.tracker.close();
        } finally {
            trip.lock.unlock();
        }
    }

    public TripSummary summary(String tenantId, String tripId) {
        TripRecord trip = require(tenantId, tripId);
        trip.lock.lock();
        try {
            return trip.tracker.summary();
        } finally {
            trip.lock.unlock();
        }
    }

    public TripInfo info(String tenantId, String tripId) {
        return require(tenantId, tripId).info;
    }

    public int activeTrips() {
        int n = 0;
        for (ConcurrentHashMap<String, TripRecord> tenantTrips : trips.values()) {
            for (TripRecord r : tenantTrips.values()) {
                if (!r.tracker.isClosed()) {
                    n++;
                }
            }
        }
        return n;
    }

    private TripRecord require(String tenantId, String tripId) {
        ConcurrentHashMap<String, TripRecord> tenantTrips = trips.get(tenantId);
        TripRecord r = tenantTrips == null ? null : tenantTrips.get(tripId);
        if (r == null) {
            throw new NotFoundException("TRIP_NOT_FOUND", "trip " + tripId + " not found");
        }
        return r;
    }

    private static void checkId(String field, String value) {
        if (value == null || !ID.matcher(value).matches()) {
            throw new BadRequestException("INVALID_ID", field + " must be 1-64 characters of A-Z a-z 0-9 _ . : -");
        }
    }
}
