package com.moveinsync.zones.core;

import com.moveinsync.zones.core.attribution.Ping;
import com.moveinsync.zones.core.attribution.TripSummary;
import com.moveinsync.zones.core.attribution.ZoneEvent;
import com.moveinsync.zones.core.catalog.ZoneCatalogService;
import com.moveinsync.zones.seed.DemoZones;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static com.moveinsync.zones.core.TripAttributionTest.istMillis;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The demo data shipped with the app reproduces the case-study example. */
class DemoScenarioTest {

    private final TestEnv env = new TestEnv();

    @AfterEach
    void tearDown() {
        env.close();
    }

    @Test
    void demoAirportTripIsBilled30Point4CityAnd11Point6Airport() {
        for (ZoneCatalogService.ZoneDraft d : DemoZones.acme()) {
            env.catalog.create("acme", "seed", d);
        }
        env.awaitIndex("acme");
        List<Ping> pings = new ArrayList<>();
        for (int i = 0; i <= 84; i++) {
            pings.add(new Ping(DemoZones.TRIP_START_LAT + i * 0.5 * DemoZones.DEG_PER_KM, DemoZones.TRIP_LNG,
                    istMillis(14, 0) + i * 60_000L));
        }
        TripSummary s = env.queries.attribute("acme", "demo", pings, null);
        assertEquals(new BigDecimal("42.000"), s.totalKm());
        assertEquals(2, s.distanceByZone().size());
        assertEquals("BLR_AIRPORT", s.distanceByZone().get(0).zoneId());
        assertEquals(new BigDecimal("11.600"), s.distanceByZone().get(0).km());
        assertEquals("BLR_CITY", s.distanceByZone().get(1).zoneId());
        assertEquals(new BigDecimal("30.400"), s.distanceByZone().get(1).km());
    }

    @Test
    void demoNightTripThroughTheQuarryRaisesOneAlert() {
        for (ZoneCatalogService.ZoneDraft d : DemoZones.acme()) {
            env.catalog.create("acme", "seed", d);
        }
        env.awaitIndex("acme");
        List<Ping> pings = new ArrayList<>();
        for (int i = 0; i <= 20; i++) {
            pings.add(new Ping(13.04, 77.60 + i * 0.005, istMillis(23, 0) + i * 30_000L));
        }
        TripSummary s = env.queries.attribute("acme", "night", pings, null);
        int alerts = 0;
        for (ZoneEvent e : s.events()) {
            if (e.type() == ZoneEvent.Type.NO_GO_ALERT) {
                alerts++;
                assertEquals("QUARRY_NOGO", e.zoneId());
                assertEquals(77.64, e.lng(), 1e-6);
            }
        }
        assertEquals(1, alerts);
    }
}
