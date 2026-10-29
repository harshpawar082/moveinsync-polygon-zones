package com.moveinsync.zones.core.zone;

import com.moveinsync.zones.core.geo.Polygon;

import java.time.Instant;

/**
 * One immutable version of one zone. Every edit appends a new ZoneVersion;
 * nothing is ever updated in place, so old versions stay queryable.
 *
 * @param version        per-zone version: 1, 2, 3, ...
 * @param catalogVersion per-tenant version of the whole zone catalog at which
 *                       this version was committed. A snapshot "at catalog
 *                       version N" contains, for each zone, its latest version
 *                       with catalogVersion <= N.
 * @param deleted        true for a tombstone (the zone was removed)
 * @param priority       higher wins when billable zones overlap
 */
public record ZoneVersion(
        String tenantId,
        String zoneId,
        int version,
        String name,
        ZoneType type,
        int priority,
        Polygon polygon,
        boolean deleted,
        long catalogVersion,
        String author,
        String reason,
        Instant createdAt) {
}
