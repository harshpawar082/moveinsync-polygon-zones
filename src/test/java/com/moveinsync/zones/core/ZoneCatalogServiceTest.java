package com.moveinsync.zones.core;

import com.moveinsync.zones.core.error.BadRequestException;
import com.moveinsync.zones.core.error.ConflictException;
import com.moveinsync.zones.core.error.NotFoundException;
import com.moveinsync.zones.core.validation.InvalidPolygonException;
import com.moveinsync.zones.core.zone.ZoneType;
import com.moveinsync.zones.core.zone.ZoneVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static com.moveinsync.zones.core.TestEnv.draft;
import static com.moveinsync.zones.core.TestShapes.coords;
import static com.moveinsync.zones.core.TestShapes.rect;
import static com.moveinsync.zones.core.TestShapes.ring;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZoneCatalogServiceTest {

    private final TestEnv env = new TestEnv();

    @AfterEach
    void tearDown() {
        env.close();
    }

    @Test
    void everyEditIsANewVersionAndOldVersionsStayQueryable() {
        ZoneVersion v1 = env.catalog.create("t1", "admin", draft("AIRPORT", ZoneType.AIRPORT, 5, coords(rect(77.0, 12.0, 77.1, 12.1))));
        ZoneVersion v2 = env.catalog.update("t1", "admin", "AIRPORT", 1, draft("AIRPORT", ZoneType.AIRPORT, 5, coords(rect(77.2, 12.0, 77.3, 12.1))));
        assertEquals(1, v1.version());
        assertEquals(2, v2.version());
        assertEquals(2, env.catalog.history("t1", "AIRPORT").size());
        env.awaitIndex("t1");

        // live index: new shape only
        assertEquals(0, env.queries.query("t1", 12.05, 77.05, null).zones().size());
        assertEquals(1, env.queries.query("t1", 12.05, 77.25, null).zones().size());
        // historic catalog version 1: old shape
        assertEquals(1, env.queries.query("t1", 12.05, 77.05, 1L).zones().size());
        assertEquals(0, env.queries.query("t1", 12.05, 77.25, 1L).zones().size());
        // and the old zone version itself
        assertTrue(env.catalog.version("t1", "AIRPORT", 1).polygon().contains(77.05, 12.05));
    }

    @Test
    void staleEditIsRejectedWithConflict() {
        env.catalog.create("t1", "a", draft("Z", ZoneType.TARIFF, 1, coords(rect(0, 0, 1, 1))));
        env.catalog.update("t1", "a", "Z", 1, draft("Z", ZoneType.TARIFF, 1, coords(rect(0, 0, 2, 2))));
        ConflictException e = assertThrows(ConflictException.class,
                () -> env.catalog.update("t1", "b", "Z", 1, draft("Z", ZoneType.TARIFF, 1, coords(rect(0, 0, 3, 3)))));
        assertEquals("VERSION_CONFLICT", e.code());
        assertEquals(2, env.catalog.latest("t1", "Z").version());
    }

    @Test
    void createIsNotRepeatable() {
        env.catalog.create("t1", "a", draft("Z", ZoneType.TARIFF, 1, coords(rect(0, 0, 1, 1))));
        assertThrows(ConflictException.class,
                () -> env.catalog.create("t1", "a", draft("Z", ZoneType.TARIFF, 1, coords(rect(0, 0, 1, 1)))));
        assertEquals(1, env.repo.latestCatalogVersion("t1"));
    }

    @Test
    void deleteLeavesATombstoneAndTheZoneCanComeBack() {
        env.catalog.create("t1", "a", draft("Z", ZoneType.NO_GO, 0, coords(rect(0, 0, 1, 1))));
        env.catalog.delete("t1", "a", "Z", 1, "no longer needed");
        assertThrows(NotFoundException.class, () -> env.catalog.latest("t1", "Z"));
        assertEquals(0, env.catalog.listActive("t1").size());
        assertEquals(2, env.catalog.history("t1", "Z").size());
        ZoneVersion back = env.catalog.create("t1", "a", draft("Z", ZoneType.NO_GO, 0, coords(rect(0, 0, 1, 1))));
        assertEquals(3, back.version());
    }

    @Test
    void invalidPolygonIsRejectedBeforeAnythingIsStored() {
        assertThrows(InvalidPolygonException.class,
                () -> env.catalog.create("t1", "a", draft("BAD", ZoneType.TARIFF, 1, coords(ring(0, 0, 2, 2, 2, 0, 0, 2)))));
        assertEquals(0, env.repo.latestCatalogVersion("t1"));
        assertEquals(1, env.metrics.rejected.get());
    }

    @Test
    void badFieldsAreRejected() {
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> env.catalog.create("t1", "a", new com.moveinsync.zones.core.catalog.ZoneCatalogService.ZoneDraft(
                        "Z", "", null, 5000, coords(rect(0, 0, 1, 1)), null)));
        assertEquals("INVALID_ZONE", e.code());
        assertEquals(3, e.details().size());
        assertThrows(BadRequestException.class,
                () -> env.catalog.create("t1", "a", draft("bad id!", ZoneType.TARIFF, 1, coords(rect(0, 0, 1, 1)))));
    }

    @Test
    void tenantsNeverSeeEachOthersZones() {
        env.catalog.create("acme", "a", draft("AIRPORT", ZoneType.AIRPORT, 5, coords(rect(77.0, 12.0, 77.1, 12.1))));
        env.awaitIndex("acme");
        assertEquals(1, env.queries.query("acme", 12.05, 77.05, null).zones().size());
        assertEquals(0, env.queries.query("globex", 12.05, 77.05, null).zones().size());
        assertThrows(NotFoundException.class, () -> env.catalog.latest("globex", "AIRPORT"));
        assertThrows(NotFoundException.class, () -> env.catalog.history("globex", "AIRPORT"));
    }
}
