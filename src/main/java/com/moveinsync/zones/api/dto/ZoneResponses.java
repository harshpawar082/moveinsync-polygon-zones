package com.moveinsync.zones.api.dto;

import com.moveinsync.zones.core.zone.ZoneType;
import com.moveinsync.zones.core.zone.ZoneVersion;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class ZoneResponses {

    private ZoneResponses() {
    }

    /** Full zone version including its geometry. */
    public record Zone(String zoneId, int version, String name, ZoneType type, int priority, boolean deleted,
                       long catalogVersion, String author, String reason, Instant createdAt, int vertexCount,
                       GeoJsonPolygon geometry) {

        public static Zone of(ZoneVersion v) {
            return new Zone(v.zoneId(), v.version(), v.name(), v.type(), v.priority(), v.deleted(), v.catalogVersion(),
                    v.author(), v.reason(), v.createdAt(), v.polygon().vertexCount(), GeoJsonPolygon.of(v.polygon()));
        }
    }

    /** Zone version without geometry (lists can be large). */
    public record ZoneSummary(String zoneId, int version, String name, ZoneType type, int priority, boolean deleted,
                              long catalogVersion, String author, String reason, Instant createdAt, int vertexCount) {

        public static ZoneSummary of(ZoneVersion v) {
            return new ZoneSummary(v.zoneId(), v.version(), v.name(), v.type(), v.priority(), v.deleted(),
                    v.catalogVersion(), v.author(), v.reason(), v.createdAt(), v.polygon().vertexCount());
        }

        public static List<ZoneSummary> of(List<ZoneVersion> versions) {
            List<ZoneSummary> out = new ArrayList<>(versions.size());
            for (ZoneVersion v : versions) {
                out.add(of(v));
            }
            return out;
        }
    }

    /** A zone that contains a queried point. */
    public record ZoneMatch(String zoneId, int version, String name, ZoneType type, int priority) {

        public static List<ZoneMatch> of(List<ZoneVersion> zones) {
            List<ZoneMatch> out = new ArrayList<>(zones.size());
            for (ZoneVersion z : zones) {
                out.add(new ZoneMatch(z.zoneId(), z.version(), z.name(), z.type(), z.priority()));
            }
            return out;
        }
    }
}
