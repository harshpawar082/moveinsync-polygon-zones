package com.moveinsync.zones.core.index;

import com.moveinsync.zones.core.geo.BoundingBox;
import com.moveinsync.zones.core.zone.ZoneVersion;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything needed to answer zone questions for ONE tenant at ONE catalog
 * version: the zone versions and an index built over exactly those zones.
 *
 * A snapshot is immutable. Readers grab the current snapshot once and use
 * only it, so they can never see a half-built index or a mix of two versions.
 */
public final class ZoneSnapshot {

    /** Highest priority first, then zoneId: a fixed order so answers are deterministic. */
    public static final Comparator<ZoneVersion> RESULT_ORDER =
            Comparator.comparingInt(ZoneVersion::priority).reversed().thenComparing(ZoneVersion::zoneId);

    private final String tenantId;
    private final long catalogVersion;
    private final List<ZoneVersion> zones;
    private final Map<String, ZoneVersion> byId;
    private final SpatialIndex index;
    private final Instant builtAt;
    private final long buildMillis;

    public ZoneSnapshot(String tenantId, long catalogVersion, List<ZoneVersion> zones, SpatialIndex index,
                        Instant builtAt, long buildMillis) {
        this.tenantId = tenantId;
        this.catalogVersion = catalogVersion;
        this.zones = Collections.unmodifiableList(new ArrayList<>(zones));
        Map<String, ZoneVersion> map = new HashMap<>();
        for (ZoneVersion z : zones) {
            map.put(z.zoneId(), z);
        }
        this.byId = Collections.unmodifiableMap(map);
        this.index = index;
        this.builtAt = builtAt;
        this.buildMillis = buildMillis;
    }

    public static ZoneSnapshot empty(String tenantId) {
        return new ZoneSnapshot(tenantId, 0L, List.of(), new BruteForceIndex(List.of()), Instant.EPOCH, 0L);
    }

    /** All zones containing the point (boundary counts as inside), highest priority first. */
    public List<ZoneVersion> query(double lat, double lng) {
        int[] candidates = index.candidatesAt(lng, lat);
        List<ZoneVersion> out = new ArrayList<>(2);
        for (int id : candidates) {
            ZoneVersion zone = zones.get(id);
            if (zone.polygon().contains(lng, lat)) {
                out.add(zone);
            }
        }
        if (out.size() > 1) {
            out.sort(RESULT_ORDER);
        }
        return out;
    }

    /** Zones whose bounding box intersects the box. */
    public List<ZoneVersion> candidatesIn(BoundingBox box) {
        int[] ids = index.candidatesIn(box);
        List<ZoneVersion> out = new ArrayList<>(ids.length);
        for (int id : ids) {
            out.add(zones.get(id));
        }
        return out;
    }

    public ZoneVersion zone(String zoneId) {
        return byId.get(zoneId);
    }

    public String tenantId() {
        return tenantId;
    }

    public long catalogVersion() {
        return catalogVersion;
    }

    public List<ZoneVersion> zones() {
        return zones;
    }

    public SpatialIndex index() {
        return index;
    }

    public Instant builtAt() {
        return builtAt;
    }

    public long buildMillis() {
        return buildMillis;
    }
}
