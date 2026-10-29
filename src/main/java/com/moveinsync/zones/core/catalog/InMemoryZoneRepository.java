package com.moveinsync.zones.core.catalog;

import com.moveinsync.zones.core.zone.ZoneVersion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe in-memory store. One lock per tenant; tenants never block each other. */
public class InMemoryZoneRepository implements ZoneRepository {

    private static final class TenantZones {
        long catalogVersion;
        final Map<String, List<ZoneVersion>> history = new HashMap<>();
    }

    private final ConcurrentHashMap<String, TenantZones> tenants = new ConcurrentHashMap<>();

    @Override
    public void append(ZoneVersion v) {
        TenantZones t = tenants.computeIfAbsent(v.tenantId(), id -> new TenantZones());
        synchronized (t) {
            if (v.catalogVersion() != t.catalogVersion + 1) {
                throw new IllegalStateException("catalog version must be " + (t.catalogVersion + 1)
                        + " but was " + v.catalogVersion());
            }
            List<ZoneVersion> h = t.history.computeIfAbsent(v.zoneId(), id -> new ArrayList<>());
            if (v.version() != h.size() + 1) {
                throw new IllegalStateException("zone version must be " + (h.size() + 1) + " but was " + v.version());
            }
            h.add(v);
            t.catalogVersion = v.catalogVersion();
        }
    }

    @Override
    public long latestCatalogVersion(String tenantId) {
        TenantZones t = tenants.get(tenantId);
        if (t == null) {
            return 0L;
        }
        synchronized (t) {
            return t.catalogVersion;
        }
    }

    @Override
    public Optional<ZoneVersion> latest(String tenantId, String zoneId) {
        TenantZones t = tenants.get(tenantId);
        if (t == null) {
            return Optional.empty();
        }
        synchronized (t) {
            List<ZoneVersion> h = t.history.get(zoneId);
            if (h == null || h.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(h.get(h.size() - 1));
        }
    }

    @Override
    public List<ZoneVersion> history(String tenantId, String zoneId) {
        TenantZones t = tenants.get(tenantId);
        if (t == null) {
            return List.of();
        }
        synchronized (t) {
            List<ZoneVersion> h = t.history.get(zoneId);
            if (h == null) {
                return List.of();
            }
            return List.copyOf(h);
        }
    }

    @Override
    public List<ZoneVersion> activeAt(String tenantId, long catalogVersion) {
        TenantZones t = tenants.get(tenantId);
        if (t == null) {
            return List.of();
        }
        List<ZoneVersion> out = new ArrayList<>();
        synchronized (t) {
            for (List<ZoneVersion> h : t.history.values()) {
                ZoneVersion found = null;
                // versions are in commit order, so walk back to the newest one not after catalogVersion
                for (int i = h.size() - 1; i >= 0; i--) {
                    if (h.get(i).catalogVersion() <= catalogVersion) {
                        found = h.get(i);
                        break;
                    }
                }
                if (found != null && !found.deleted()) {
                    out.add(found);
                }
            }
        }
        return out;
    }

    @Override
    public Set<String> tenants() {
        return new TreeSet<>(tenants.keySet());
    }
}
