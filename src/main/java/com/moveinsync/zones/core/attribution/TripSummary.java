package com.moveinsync.zones.core.attribution;

import com.moveinsync.zones.core.zone.ZoneType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Billing view of a trip.
 *
 * @param billingCatalogVersion the zone catalog version used for tariff zones (pinned at trip start)
 * @param distanceByZone        km per billable zone; parts add up exactly to totalKm
 */
public record TripSummary(
        String tripId,
        long billingCatalogVersion,
        BigDecimal totalKm,
        List<ZoneDistance> distanceByZone,
        List<ZoneEvent> events,
        List<OpenZone> openZones,
        int pingsAccepted,
        int pingsDuplicate,
        int pingsOutOfOrder,
        int pingsInvalid,
        boolean closed) {

    /** Key used for distance driven outside every billable zone. */
    public static final String UNZONED = "UNZONED";

    public record ZoneDistance(String zoneId, String zoneName, ZoneType zoneType, BigDecimal km) {
    }

    public record OpenZone(String zoneId, String zoneName, ZoneType zoneType, Instant enteredAt, long dwellMillisSoFar) {
    }
}
