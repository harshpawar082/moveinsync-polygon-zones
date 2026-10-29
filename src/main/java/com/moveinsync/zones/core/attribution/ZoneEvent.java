package com.moveinsync.zones.core.attribution;

import com.moveinsync.zones.core.zone.ZoneType;

import java.time.Instant;

/**
 * A zone entry, exit, or no-go alert on a trip.
 *
 * @param dwellMillis for EXIT: time spent inside since the matching ENTRY; null otherwise
 * @param cause       for EXIT: CROSSED_BOUNDARY, TRIP_END, or ZONE_CHANGED (the zone was edited
 *                    or deleted while the cab was inside); null otherwise
 */
public record ZoneEvent(
        Type type,
        String zoneId,
        String zoneName,
        ZoneType zoneType,
        int zoneVersion,
        Instant at,
        Long dwellMillis,
        String cause,
        double lat,
        double lng) {

    public enum Type {
        ENTRY,
        EXIT,
        NO_GO_ALERT
    }
}
