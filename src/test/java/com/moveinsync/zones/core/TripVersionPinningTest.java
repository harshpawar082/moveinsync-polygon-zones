package com.moveinsync.zones.core;

import com.moveinsync.zones.core.attribution.Ping;
import com.moveinsync.zones.core.attribution.TripSummary;
import com.moveinsync.zones.core.attribution.ZoneEvent;
import com.moveinsync.zones.core.trip.TripService;
import com.moveinsync.zones.core.zone.ZoneType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static com.moveinsync.zones.core.TestEnv.draft;
import static com.moveinsync.zones.core.TestShapes.coords;
import static com.moveinsync.zones.core.TestShapes.rect;
import static com.moveinsync.zones.core.TripAttributionTest.istMillis;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rule for zones edited while a trip is running. */
class TripVersionPinningTest {

    private final TestEnv env = new TestEnv();

    @AfterEach
    void tearDown() {
        env.close();
    }

    private static List<Ping> eastbound(double fromLng, double toLng, int steps, long startMillis) {
        List<Ping> out = new ArrayList<>();
        for (int i = 0; i <= steps; i++) {
            out.add(new Ping(12.95, fromLng + (toLng - fromLng) * i / steps, startMillis + i * 60_000L));
        }
        return out;
    }

    private static BigDecimal km(TripSummary s, String zoneId) {
        for (TripSummary.ZoneDistance d : s.distanceByZone()) {
            if (d.zoneId().equals(zoneId)) {
                return d.km();
            }
        }
        return BigDecimal.ZERO.setScale(3);
    }

    @Test
    void runningTripKeepsTheTariffZonesItStartedWith() {
        String t = "t1";
        env.catalog.create(t, "a", draft("CITY", ZoneType.TARIFF, 1, coords(rect(77.50, 12.90, 77.60, 13.00))));
        env.catalog.create(t, "a", draft("AIRPORT", ZoneType.AIRPORT, 5, coords(rect(77.60, 12.90, 77.70, 13.00))));
        env.awaitIndex(t);
        List<Ping> path = eastbound(77.52, 77.68, 16, istMillis(14, 0));

        TripService.TripInfo first = env.trips.start(t, "T1", "CAB1", Set.of("emp-1"));
        env.trips.ingest(t, "T1", path.subList(0, 8));

        // admin moves the city/airport border east while T1 is running
        env.catalog.update(t, "a", "CITY", 1, draft("CITY", ZoneType.TARIFF, 1, coords(rect(77.50, 12.90, 77.65, 13.00))));
        env.catalog.update(t, "a", "AIRPORT", 1, draft("AIRPORT", ZoneType.AIRPORT, 5, coords(rect(77.65, 12.90, 77.70, 13.00))));
        env.awaitIndex(t);

        env.trips.ingest(t, "T1", path.subList(8, path.size()));
        TripSummary oldRules = env.trips.end(t, "T1");
        assertEquals(first.billingCatalogVersion(), oldRules.billingCatalogVersion());

        TripService.TripInfo second = env.trips.start(t, "T2", "CAB2", Set.of("emp-2"));
        env.trips.ingest(t, "T2", path);
        TripSummary newRules = env.trips.end(t, "T2");

        assertTrue(second.billingCatalogVersion() > first.billingCatalogVersion());
        assertEquals(oldRules.totalKm(), newRules.totalKm());
        // border at 77.60 for T1 (8 of 16 steps in the city), at 77.65 for T2 (13 of 16)
        double total = oldRules.totalKm().doubleValue();
        assertEquals(0.5, km(oldRules, "CITY").doubleValue() / total, 0.002);
        assertEquals(13.0 / 16.0, km(newRules, "CITY").doubleValue() / total, 0.002);
        assertNotEquals(km(oldRules, "CITY"), km(newRules, "CITY"));
    }

    @Test
    void shrunkNoGoZoneAppliesImmediatelyToARunningTrip() {
        String t = "t1";
        env.catalog.create(t, "a", draft("NOGO", ZoneType.NO_GO, 0, coords(rect(77.60, 12.90, 77.70, 13.00))));
        env.awaitIndex(t);

        env.trips.start(t, "NIGHT1", "CAB9", Set.of());
        env.trips.ingest(t, "NIGHT1", List.of(
                new Ping(12.95, 77.55, istMillis(20, 50)),
                new Ping(12.95, 77.56, istMillis(20, 55))));

        // 9 PM: the admin shrinks the zone to its eastern part
        env.catalog.update(t, "a", "NOGO", 1, draft("NOGO", ZoneType.NO_GO, 0, coords(rect(77.66, 12.90, 77.70, 13.00))));
        env.awaitIndex(t);

        // 77.62 is inside the OLD shape only; 77.68 is inside the new one
        TripService.IngestResult r = env.trips.ingest(t, "NIGHT1", List.of(
                new Ping(12.95, 77.62, istMillis(22, 30)),
                new Ping(12.95, 77.68, istMillis(22, 40))));

        List<ZoneEvent> alerts = new ArrayList<>();
        for (ZoneEvent e : r.events()) {
            if (e.type() == ZoneEvent.Type.NO_GO_ALERT) {
                alerts.add(e);
            }
        }
        assertEquals(1, alerts.size());
        assertEquals(77.66, alerts.get(0).lng(), 1e-6);   // entered the NEW boundary
        assertEquals(2, alerts.get(0).zoneVersion());
        assertEquals(1, env.metrics.noGoAlerts.get());
    }

    @Test
    void replayingPingsChangesNothing() {
        String t = "t1";
        env.catalog.create(t, "a", draft("CITY", ZoneType.TARIFF, 1, coords(rect(77.50, 12.90, 77.60, 13.00))));
        env.awaitIndex(t);
        List<Ping> path = eastbound(77.52, 77.68, 16, istMillis(14, 0));

        env.trips.start(t, "R1", "CAB1", Set.of());
        env.trips.ingest(t, "R1", path);
        TripSummary once = env.trips.summary(t, "R1");
        TripService.IngestResult replay = env.trips.ingest(t, "R1", path);
        TripSummary twice = env.trips.summary(t, "R1");

        assertEquals(0, replay.accepted());
        assertEquals(once.distanceByZone(), twice.distanceByZone());
        assertEquals(once.events(), twice.events());

        // a shuffled batch gives the same answer as an ordered one
        List<Ping> shuffled = new ArrayList<>(path);
        Collections.shuffle(shuffled, new java.util.Random(3));
        env.trips.start(t, "R2", "CAB1", Set.of());
        env.trips.ingest(t, "R2", shuffled);
        assertEquals(once.distanceByZone(), env.trips.summary(t, "R2").distanceByZone());

        // starting the same trip again is a harmless no-op
        assertEquals(env.trips.info(t, "R1"), env.trips.start(t, "R1", "CAB1", Set.of()));
    }
}
