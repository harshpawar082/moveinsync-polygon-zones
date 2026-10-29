package com.moveinsync.zones.core.catalog;

import com.moveinsync.zones.core.error.BadRequestException;
import com.moveinsync.zones.core.error.NotFoundException;
import com.moveinsync.zones.core.index.SnapshotBuilder;
import com.moveinsync.zones.core.index.ZoneSnapshot;
import com.moveinsync.zones.core.metrics.ZoneMetrics;
import com.moveinsync.zones.core.zone.ZoneVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the live index of every tenant.
 *
 * Live edits without downtime:
 * 1. an edit is committed to the repository (catalog version N);
 * 2. a background thread builds a brand-new snapshot for version N;
 * 3. the new snapshot replaces the old one with ONE atomic reference swap.
 * Readers call {@link #current} once per request and keep using that object,
 * so a query sees either the old index or the new one, never a half-built one,
 * and never waits for a rebuild (no locks on the read path).
 *
 * Versions only move forward: a slower, older build can never replace a newer
 * snapshot (compare-and-set loop in {@link #swapForward}).
 *
 * If a build fails, the old snapshot keeps serving, the failure is counted, and
 * the build is retried with exponential backoff.
 */
public final class SnapshotManager implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SnapshotManager.class);

    public record Settings(int historyCacheSize, long retryBaseMillis, long retryMaxMillis, int rebuildThreads) {
        public static Settings defaults() {
            return new Settings(32, 500, 30_000, 2);
        }
    }

    /** What an operator sees for one tenant. */
    public record IndexStatus(String tenantId, long activeCatalogVersion, long latestCatalogVersion,
                              long pendingVersions, int zoneCount, long lastBuildMillis,
                              int consecutiveFailures, Instant activeSince, String index) {
    }

    private static final class TenantSlot {
        final AtomicReference<ZoneSnapshot> current;
        final AtomicBoolean scheduled = new AtomicBoolean(false);
        final AtomicLong firstPendingNanos = new AtomicLong(0);
        volatile int consecutiveFailures;

        TenantSlot(ZoneSnapshot initial) {
            this.current = new AtomicReference<>(initial);
        }
    }

    private final ZoneRepository repository;
    private final SnapshotBuilder builder;
    private final ZoneMetrics metrics;
    private final Settings settings;
    private final ConcurrentHashMap<String, TenantSlot> slots = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor;
    private final Map<String, ZoneSnapshot> history;

    public SnapshotManager(ZoneRepository repository, SnapshotBuilder builder, ZoneMetrics metrics, Settings settings) {
        this.repository = repository;
        this.builder = builder;
        this.metrics = metrics;
        this.settings = settings;
        this.executor = Executors.newScheduledThreadPool(settings.rebuildThreads(), r -> {
            Thread t = new Thread(r, "zone-index-rebuild");
            t.setDaemon(true);
            return t;
        });
        int capacity = settings.historyCacheSize();
        // LRU cache of OLD snapshots (for trips pinned to an earlier version).
        // Snapshots are immutable, so a cached one can never be stale; evicting
        // one only costs a rebuild the next time it is asked for.
        this.history = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ZoneSnapshot> eldest) {
                return size() > capacity;
            }
        };
    }

    /** Builds the index of every known tenant synchronously. Call once at startup. */
    public void initialize() {
        for (String tenant : repository.tenants()) {
            long version = repository.latestCatalogVersion(tenant);
            ZoneSnapshot snap = builder.build(tenant, version, repository.activeAt(tenant, version));
            swapForward(slot(tenant), snap);
        }
    }

    /** The live snapshot. Lock-free; never blocks on a rebuild. */
    public ZoneSnapshot current(String tenantId) {
        return slot(tenantId).current.get();
    }

    /** A snapshot of an exact catalog version (current, cached, or rebuilt from history). */
    public ZoneSnapshot at(String tenantId, long catalogVersion) {
        if (catalogVersion < 0) {
            throw new BadRequestException("INVALID_CATALOG_VERSION", "catalog version must be >= 0");
        }
        ZoneSnapshot live = current(tenantId);
        if (live.catalogVersion() == catalogVersion) {
            return live;
        }
        if (catalogVersion > repository.latestCatalogVersion(tenantId)) {
            throw new NotFoundException("CATALOG_VERSION_NOT_FOUND", "catalog version " + catalogVersion + " does not exist");
        }
        String key = tenantId + "@" + catalogVersion;
        synchronized (history) {
            ZoneSnapshot cached = history.get(key);
            if (cached != null) {
                return cached;
            }
        }
        ZoneSnapshot built = builder.build(tenantId, catalogVersion, repository.activeAt(tenantId, catalogVersion));
        synchronized (history) {
            history.put(key, built);
        }
        return built;
    }

    /** Asks for a background rebuild. Many edits in a burst collapse into one or two builds. */
    public void requestRebuild(String tenantId) {
        TenantSlot slot = slot(tenantId);
        slot.firstPendingNanos.compareAndSet(0, System.nanoTime());
        if (slot.scheduled.compareAndSet(false, true)) {
            executor.execute(() -> rebuild(tenantId, slot));
        }
    }

    private void rebuild(String tenantId, TenantSlot slot) {
        // Clear the flag BEFORE reading the target version, so an edit committed
        // while we build schedules another round instead of being missed.
        slot.scheduled.set(false);
        long target = repository.latestCatalogVersion(tenantId);
        if (target <= slot.current.get().catalogVersion()) {
            return;
        }
        try {
            List<ZoneVersion> zones = repository.activeAt(tenantId, target);
            ZoneSnapshot snap = builder.build(tenantId, target, zones);
            if (swapForward(slot, snap)) {
                slot.consecutiveFailures = 0;
                long staleness = 0;
                if (target >= repository.latestCatalogVersion(tenantId)) {
                    long since = slot.firstPendingNanos.getAndSet(0);
                    if (since > 0) {
                        staleness = (System.nanoTime() - since) / 1_000_000;
                    }
                }
                metrics.indexSwapped(tenantId, target, snap.buildMillis(), staleness, zones.size());
            }
        } catch (RuntimeException e) {
            slot.consecutiveFailures++;
            metrics.indexRebuildFailed(tenantId);
            long delay = retryDelayMillis(slot.consecutiveFailures);
            LOG.warn("index rebuild failed tenant={} targetVersion={} attempt={} retryInMs={} error={}",
                    tenantId, target, slot.consecutiveFailures, delay, e.getClass().getSimpleName());
            executor.schedule(() -> requestRebuild(tenantId), delay, TimeUnit.MILLISECONDS);
        }
    }

    long retryDelayMillis(int failures) {
        long delay = settings.retryBaseMillis();
        for (int i = 1; i < failures && delay < settings.retryMaxMillis(); i++) {
            delay *= 2;
        }
        long jitter = (long) (Math.random() * delay * 0.2);
        return Math.min(settings.retryMaxMillis(), delay + jitter);
    }

    /** Publishes snap unless a newer (or equal) version is already live. */
    private boolean swapForward(TenantSlot slot, ZoneSnapshot snap) {
        while (true) {
            ZoneSnapshot live = slot.current.get();
            if (snap.catalogVersion() <= live.catalogVersion() && live.catalogVersion() != 0) {
                return false;
            }
            if (slot.current.compareAndSet(live, snap)) {
                return true;
            }
        }
    }

    /** Blocks until the live index has reached the version (or the timeout passes). */
    public boolean awaitVersion(String tenantId, long catalogVersion, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (current(tenantId).catalogVersion() < catalogVersion) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    public IndexStatus status(String tenantId) {
        TenantSlot slot = slot(tenantId);
        ZoneSnapshot live = slot.current.get();
        long latest = repository.latestCatalogVersion(tenantId);
        return new IndexStatus(tenantId, live.catalogVersion(), latest, Math.max(0, latest - live.catalogVersion()),
                live.zones().size(), live.buildMillis(), slot.consecutiveFailures, live.builtAt(),
                live.index().describe());
    }

    /** Sum over tenants of committed-but-not-yet-live versions (alert if it stays above 0). */
    public long totalPendingVersions() {
        long total = 0;
        for (String tenant : new ArrayList<>(slots.keySet())) {
            total += status(tenant).pendingVersions();
        }
        return total;
    }

    public int failingTenants() {
        int n = 0;
        for (TenantSlot slot : slots.values()) {
            if (slot.consecutiveFailures > 0) {
                n++;
            }
        }
        return n;
    }

    private TenantSlot slot(String tenantId) {
        return slots.computeIfAbsent(tenantId, t -> new TenantSlot(ZoneSnapshot.empty(t)));
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
