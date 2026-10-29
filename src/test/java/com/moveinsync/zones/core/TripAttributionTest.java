package com.moveinsync.zones.core;

import com.moveinsync.zones.core.attribution.DistanceRounding;
import com.moveinsync.zones.core.attribution.NightWindow;
import com.moveinsync.zones.core.attribution.Ping;
import com.moveinsync.zones.core.attribution.TripSummary;
import com.moveinsync.zones.core.attribution.TripTracker;
import com.moveinsync.zones.core.attribution.ZoneEvent;
import com.moveinsync.zones.core.geo.Haversine;
import com.moveinsync.zones.core.index.GridSnapshotBuilder;
import com.moveinsync.zones.core.index.ZoneSnapshot;
import com.moveinsync.zones.core.zone.ZoneType;
import com.moveinsync.zones.core.zone.ZoneVersion;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.moveinsync.zones.core.TestShapes.polygon;
import static com.moveinsync.zones.core.TestShapes.rect;
import static com.moveinsync.zones.core.TestShapes.zone;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TripAttributionTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final NightWindow NIGHT = new NightWindow(LocalTime.of(22, 0), LocalTime.of(6, 0), IST);
    /** Degrees of latitude per km on our spherical earth. */
    static final double DEG_PER_KM = 180.0 / (Math.PI * Haversine.EARTH_RADIUS_M / 1000.0);

    static final double START_LAT = 12.90;
    static final double LNG = 77.62;
    static final double BOUNDARY_LAT = START_LAT + 30.4 * DEG_PER_KM;

    static ZoneSnapshot snapshot(ZoneVersion... zones) {
        return new GridSnapshotBuilder(0.01, 4096).build("t1", 1, List.of(zones));
    }

    static long istMillis(int hour, int minute) {
        return ZonedDateTime.of(2026, 10, 30, hour, minute, 0, 0, IST).toInstant().toEpochMilli();
    }

    /** Straight north from START_LAT, one ping per 0.5 km, one ping per minute. */
    static List<Ping> northboundPath(double km, long startMillis) {
        List<Ping> pings = new ArrayList<>();
        int steps = (int) Math.round(km / 0.5);
        for (int i = 0; i <= steps; i++) {
            pings.add(new Ping(START_LAT + i * 0.5 * DEG_PER_KM, LNG, startMillis + i * 60_000L));
        }
        return pings;
    }

    static ZoneSnapshot airportScenario() {
        ZoneVersion city = zone("BLR_CITY", ZoneType.TARIFF, 1, polygon(rect(77.45, 12.80, 77.80, BOUNDARY_LAT)));
        ZoneVersion airport = zone("BLR_AIRPORT", ZoneType.AIRPORT, 5, polygon(rect(77.55, BOUNDARY_LAT, 77.75, 13.30)));
        return snapshot(city, airport);
    }

    static TripSummary run(ZoneSnapshot snap, List<Ping> pings) {
        TripTracker tracker = new TripTracker("trip-1", snap, () -> snap, NIGHT);
        for (Ping p : pings) {
            tracker.feed(p);
        }
        return tracker.close();
    }

    static BigDecimal km(TripSummary s, String zoneId) {
        for (TripSummary.ZoneDistance d : s.distanceByZone()) {
            if (d.zoneId().equals(zoneId)) {
                return d.km();
            }
        }
        return BigDecimal.ZERO.setScale(3);
    }

    @Test
    void airportDropIsSplitExactlyAtTheBoundary() {
        TripSummary s = run(airportScenario(), northboundPath(42.0, istMillis(14, 0)));
        assertEquals(new BigDecimal("42.000"), s.totalKm());
        assertEquals(new BigDecimal("30.400"), km(s, "BLR_CITY"));
        assertEquals(new BigDecimal("11.600"), km(s, "BLR_AIRPORT"));
    }

    @Test
    void entryAndExitEventsCarryInterpolatedTimesAndDwell() {
        TripSummary s = run(airportScenario(), northboundPath(42.0, istMillis(14, 0)));
        List<ZoneEvent> ev = s.events();
        assertEquals(ZoneEvent.Type.ENTRY, ev.get(0).type());
        assertEquals("BLR_CITY", ev.get(0).zoneId());

        ZoneEvent exitCity = ev.get(1);
        assertEquals(ZoneEvent.Type.EXIT, exitCity.type());
        assertEquals("BLR_CITY", exitCity.zoneId());
        // crossing after 30.4 km at 0.5 km/min = 60.8 min
        assertEquals(3_648_000L, exitCity.dwellMillis().longValue());
        assertEquals("CROSSED_BOUNDARY", exitCity.cause());

        ZoneEvent enterAirport = ev.get(2);
        assertEquals(ZoneEvent.Type.ENTRY, enterAirport.type());
        assertEquals("BLR_AIRPORT", enterAirport.zoneId());

        ZoneEvent exitAirport = ev.get(3);
        assertEquals("TRIP_END", exitAirport.cause());
        assertEquals(84 * 60_000L - 3_648_000L, exitAirport.dwellMillis().longValue());
        assertEquals(4, ev.size());
    }

    @Test
    void distanceInsideAHoleIsNotBilledToTheZone() {
        ZoneVersion ward = zone("WARD", ZoneType.TARIFF, 1, polygon(rect(77.0, 12.0, 77.10, 12.10), rect(77.04, 12.04, 77.06, 12.06)));
        ZoneSnapshot snap = snapshot(ward);
        List<Ping> path = List.of(new Ping(12.05, 77.01, 0), new Ping(12.05, 77.09, 600_000));
        TripSummary s = run(snap, path);
        double ward6 = km(s, "WARD").doubleValue();
        double hole2 = km(s, TripSummary.UNZONED).doubleValue();
        assertEquals(s.totalKm().doubleValue(), ward6 + hole2, 1e-9);
        assertEquals(0.25, hole2 / s.totalKm().doubleValue(), 0.001);
        // ENTRY WARD, EXIT (into hole), ENTRY (out of hole), EXIT at trip end
        assertEquals(4, s.events().size());
    }

    @Test
    void overlappingZonesBillTheHigherPriority() {
        ZoneVersion city = zone("CITY", ZoneType.TARIFF, 1, polygon(rect(77.0, 12.0, 77.10, 12.10)));
        ZoneVersion airport = zone("AIRPORT", ZoneType.AIRPORT, 9, polygon(rect(77.04, 12.0, 77.06, 12.10)));
        ZoneVersion limits = zone("LIMITS", ZoneType.CITY_LIMIT, 99, polygon(rect(76.0, 11.0, 78.0, 13.0)));
        TripSummary s = run(snapshot(city, airport, limits), List.of(new Ping(12.05, 77.01, 0), new Ping(12.05, 77.09, 600_000)));
        double total = s.totalKm().doubleValue();
        assertEquals(0.25, km(s, "AIRPORT").doubleValue() / total, 0.001);
        assertEquals(0.75, km(s, "CITY").doubleValue() / total, 0.001);
        assertEquals(new BigDecimal("0.000"), km(s, "LIMITS")); // city limits are never billed
    }

    @Test
    void drivingAlongASharedEdgeHasOneDeterministicWinner() {
        ZoneVersion west = zone("WEST", ZoneType.TARIFF, 1, polygon(rect(77.0, 12.0, 77.05, 12.10)));
        ZoneVersion east = zone("EAST", ZoneType.TARIFF, 1, polygon(rect(77.05, 12.0, 77.10, 12.10)));
        List<Ping> alongEdge = List.of(new Ping(12.01, 77.05, 0), new Ping(12.09, 77.05, 600_000));
        TripSummary s = run(snapshot(west, east), alongEdge);
        assertEquals(s.totalKm(), km(s, "EAST")); // equal priority -> smaller zoneId wins
        TripSummary again = run(snapshot(east, west), alongEdge);
        assertEquals(s.distanceByZone(), again.distanceByZone());
    }

    @Test
    void noGoAlertOnlyAtNight() {
        ZoneVersion noGo = zone("QUARRY", ZoneType.NO_GO, 0, polygon(rect(77.04, 12.04, 77.06, 12.06)));
        ZoneSnapshot snap = snapshot(noGo);

        TripSummary night = run(snap, List.of(new Ping(12.05, 77.01, istMillis(23, 0)), new Ping(12.05, 77.09, istMillis(23, 10))));
        assertEquals(1, count(night, ZoneEvent.Type.NO_GO_ALERT));
        assertEquals(new BigDecimal("0.000"), km(night, "QUARRY")); // never billed

        TripSummary day = run(snap, List.of(new Ping(12.05, 77.01, istMillis(14, 0)), new Ping(12.05, 77.09, istMillis(14, 10))));
        assertEquals(0, count(day, ZoneEvent.Type.NO_GO_ALERT));
        assertEquals(1, count(day, ZoneEvent.Type.ENTRY));
    }

    @Test
    void nightWindowWrapsMidnight() {
        assertTrue(NIGHT.contains(istMillis(22, 0)));
        assertTrue(NIGHT.contains(istMillis(2, 30)));
        assertFalse(NIGHT.contains(istMillis(6, 0)));
        assertFalse(NIGHT.contains(istMillis(13, 0)));
    }

    @Test
    void duplicateAndLatePingsAreDroppedAndCounted() {
        ZoneSnapshot snap = airportScenario();
        TripTracker t = new TripTracker("trip-2", snap, () -> snap, NIGHT);
        Ping p1 = new Ping(12.90, 77.62, 1_000);
        Ping p2 = new Ping(12.91, 77.62, 61_000);
        assertEquals(TripTracker.Outcome.ACCEPTED, t.feed(p1).outcome());
        assertEquals(TripTracker.Outcome.ACCEPTED, t.feed(p2).outcome());
        assertEquals(TripTracker.Outcome.DUPLICATE, t.feed(p2).outcome());
        assertEquals(TripTracker.Outcome.OUT_OF_ORDER, t.feed(new Ping(12.95, 77.62, 30_000)).outcome());
        assertEquals(TripTracker.Outcome.INVALID, t.feed(new Ping(95.0, 77.62, 90_000)).outcome());
        TripSummary s = t.summary();
        assertEquals(2, s.pingsAccepted());
        assertEquals(1, s.pingsDuplicate());
        assertEquals(1, s.pingsOutOfOrder());
        assertEquals(1, s.pingsInvalid());
    }

    @Test
    void sameInputAlwaysGivesTheSameKilometres() {
        List<Ping> path = northboundPath(42.0, istMillis(14, 0));
        TripSummary a = run(airportScenario(), path);
        TripSummary b = run(airportScenario(), path);
        assertEquals(a.distanceByZone(), b.distanceByZone());
        assertEquals(a.events(), b.events());
    }

    @Test
    void roundedPartsAlwaysAddUpToTheRoundedTotal() {
        Map<String, Double> parts = new LinkedHashMap<>();
        parts.put("A", 1000.4);
        parts.put("B", 1000.4);
        parts.put("C", 1000.4);
        Map<String, BigDecimal> km = DistanceRounding.partsInKm(parts, 3001.2);
        BigDecimal sum = km.get("A").add(km.get("B")).add(km.get("C"));
        assertEquals(DistanceRounding.totalInKm(3001.2), sum);
        assertEquals(new BigDecimal("3.001"), sum);
    }

    static int count(TripSummary s, ZoneEvent.Type type) {
        int n = 0;
        for (ZoneEvent e : s.events()) {
            if (e.type() == type) {
                n++;
            }
        }
        return n;
    }
}
