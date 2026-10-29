package com.moveinsync.zones.core.catalog;

import com.moveinsync.zones.core.error.BadRequestException;
import com.moveinsync.zones.core.error.ConflictException;
import com.moveinsync.zones.core.error.NotFoundException;
import com.moveinsync.zones.core.geo.Polygon;
import com.moveinsync.zones.core.metrics.ZoneMetrics;
import com.moveinsync.zones.core.validation.InvalidPolygonException;
import com.moveinsync.zones.core.validation.PolygonValidator;
import com.moveinsync.zones.core.zone.ZoneType;
import com.moveinsync.zones.core.zone.ZoneVersion;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Create / edit / delete zones. Every change:
 * 1. is validated BEFORE anything is stored (a bad polygon is never published);
 * 2. uses optimistic concurrency: the caller sends the version it edited
 *    (expectedVersion); if someone else changed the zone first -> 409, so two
 *    admins can never silently overwrite each other, and a retried request
 *    cannot apply twice;
 * 3. gets the next per-tenant catalog version, is appended to the repository,
 *    and triggers a background index rebuild.
 */
public final class ZoneCatalogService {

    private static final Pattern ZONE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /** What a client sends to create or replace a zone. */
    public record ZoneDraft(String zoneId, String name, ZoneType type, Integer priority,
                            List<List<double[]>> coordinates, String reason) {
    }

    private final ZoneRepository repository;
    private final SnapshotManager snapshots;
    private final PolygonValidator validator;
    private final ZoneMetrics metrics;
    private final Clock clock;
    private final ConcurrentHashMap<String, ReentrantLock> tenantLocks = new ConcurrentHashMap<>();

    public ZoneCatalogService(ZoneRepository repository, SnapshotManager snapshots, PolygonValidator validator,
                              ZoneMetrics metrics, Clock clock) {
        this.repository = repository;
        this.snapshots = snapshots;
        this.validator = validator;
        this.metrics = metrics;
        this.clock = clock;
    }

    public ZoneVersion create(String tenantId, String author, ZoneDraft draft) {
        checkZoneId(draft.zoneId());
        Polygon polygon = validated(draft);
        ZoneVersion saved = withTenantLock(tenantId, () -> {
            Optional<ZoneVersion> existing = repository.latest(tenantId, draft.zoneId());
            if (existing.isPresent() && !existing.get().deleted()) {
                throw new ConflictException("ZONE_EXISTS", "zone " + draft.zoneId() + " already exists");
            }
            int version = existing.map(z -> z.version() + 1).orElse(1);
            return append(tenantId, draft.zoneId(), version, draft, polygon, false, author);
        });
        metrics.zoneChanged(tenantId, "create");
        snapshots.requestRebuild(tenantId);
        return saved;
    }

    public ZoneVersion update(String tenantId, String author, String zoneId, int expectedVersion, ZoneDraft draft) {
        checkZoneId(zoneId);
        Polygon polygon = validated(draft);
        ZoneVersion saved = withTenantLock(tenantId, () -> {
            ZoneVersion current = requireLive(tenantId, zoneId);
            checkExpected(current, expectedVersion);
            return append(tenantId, zoneId, current.version() + 1, draft, polygon, false, author);
        });
        metrics.zoneChanged(tenantId, "update");
        snapshots.requestRebuild(tenantId);
        return saved;
    }

    public ZoneVersion delete(String tenantId, String author, String zoneId, int expectedVersion, String reason) {
        checkZoneId(zoneId);
        checkReason(reason);
        ZoneVersion saved = withTenantLock(tenantId, () -> {
            ZoneVersion current = requireLive(tenantId, zoneId);
            checkExpected(current, expectedVersion);
            ZoneDraft same = new ZoneDraft(zoneId, current.name(), current.type(), current.priority(), null, reason);
            return append(tenantId, zoneId, current.version() + 1, same, current.polygon(), true, author);
        });
        metrics.zoneChanged(tenantId, "delete");
        snapshots.requestRebuild(tenantId);
        return saved;
    }

    public ZoneVersion latest(String tenantId, String zoneId) {
        return requireLive(tenantId, zoneId);
    }

    public List<ZoneVersion> history(String tenantId, String zoneId) {
        List<ZoneVersion> history = repository.history(tenantId, zoneId);
        if (history.isEmpty()) {
            throw notFound(zoneId);
        }
        return history;
    }

    public ZoneVersion version(String tenantId, String zoneId, int version) {
        for (ZoneVersion v : history(tenantId, zoneId)) {
            if (v.version() == version) {
                return v;
            }
        }
        throw new NotFoundException("ZONE_VERSION_NOT_FOUND", "zone " + zoneId + " has no version " + version);
    }

    public List<ZoneVersion> listActive(String tenantId) {
        List<ZoneVersion> zones = new ArrayList<>(
                repository.activeAt(tenantId, repository.latestCatalogVersion(tenantId)));
        zones.sort(Comparator.comparing(ZoneVersion::zoneId));
        return zones;
    }

    // ---- helpers ---------------------------------------------------------

    private ZoneVersion append(String tenantId, String zoneId, int version, ZoneDraft draft, Polygon polygon,
                               boolean deleted, String author) {
        long catalogVersion = repository.latestCatalogVersion(tenantId) + 1;
        ZoneVersion v = new ZoneVersion(tenantId, zoneId, version, draft.name(), draft.type(), draft.priority(),
                polygon, deleted, catalogVersion, author, draft.reason(), clock.instant());
        repository.append(v);
        return v;
    }

    private Polygon validated(ZoneDraft draft) {
        List<String> problems = new ArrayList<>();
        if (draft.name() == null || draft.name().isBlank() || draft.name().length() > 120) {
            problems.add("name: required, at most 120 characters");
        }
        if (draft.type() == null) {
            problems.add("type: required (CITY_LIMIT, TARIFF, AIRPORT, NO_GO)");
        }
        if (draft.priority() == null || draft.priority() < 0 || draft.priority() > 1000) {
            problems.add("priority: required, 0..1000");
        }
        if (draft.reason() != null && draft.reason().length() > 500) {
            problems.add("reason: at most 500 characters");
        }
        if (draft.coordinates() == null) {
            problems.add("geometry: required");
        }
        if (!problems.isEmpty()) {
            metrics.zoneRejected("INVALID_ZONE");
            throw new BadRequestException("INVALID_ZONE", "zone fields are invalid", problems);
        }
        try {
            return validator.validate(draft.coordinates());
        } catch (InvalidPolygonException e) {
            metrics.zoneRejected(e.errors().get(0).code());
            throw e;
        }
    }

    private static void checkZoneId(String zoneId) {
        if (zoneId == null || !ZONE_ID.matcher(zoneId).matches()) {
            throw new BadRequestException("INVALID_ZONE_ID", "zoneId must be 1-64 characters of A-Z, a-z, 0-9, _ or -");
        }
    }

    private static void checkReason(String reason) {
        if (reason != null && reason.length() > 500) {
            throw new BadRequestException("INVALID_ZONE", "reason must be at most 500 characters");
        }
    }

    private static void checkExpected(ZoneVersion current, int expectedVersion) {
        if (current.version() != expectedVersion) {
            throw new ConflictException("VERSION_CONFLICT", "zone " + current.zoneId() + " is at version "
                    + current.version() + ", not " + expectedVersion + "; reload it and retry");
        }
    }

    private ZoneVersion requireLive(String tenantId, String zoneId) {
        Optional<ZoneVersion> latest = repository.latest(tenantId, zoneId);
        if (latest.isEmpty() || latest.get().deleted()) {
            throw notFound(zoneId);
        }
        return latest.get();
    }

    private static NotFoundException notFound(String zoneId) {
        return new NotFoundException("ZONE_NOT_FOUND", "zone " + zoneId + " not found");
    }

    private <T> T withTenantLock(String tenantId, java.util.function.Supplier<T> action) {
        ReentrantLock lock = tenantLocks.computeIfAbsent(tenantId, t -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
