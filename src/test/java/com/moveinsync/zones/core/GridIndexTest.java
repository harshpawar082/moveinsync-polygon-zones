package com.moveinsync.zones.core;

import com.moveinsync.zones.core.geo.BoundingBox;
import com.moveinsync.zones.core.geo.Polygon;
import com.moveinsync.zones.core.index.BruteForceIndex;
import com.moveinsync.zones.core.index.GridIndex;
import com.moveinsync.zones.core.index.GridSnapshotBuilder;
import com.moveinsync.zones.core.index.ZoneSnapshot;
import com.moveinsync.zones.core.zone.ZoneType;
import com.moveinsync.zones.core.zone.ZoneVersion;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GridIndexTest {

    private static List<ZoneVersion> randomZones(Random rnd, int count) {
        List<ZoneVersion> zones = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double cx = 77.4 + rnd.nextDouble() * 0.4;
            double cy = 12.8 + rnd.nextDouble() * 0.4;
            double r = 0.002 + rnd.nextDouble() * 0.03;
            int n = 8 + rnd.nextInt(120);
            Polygon p = TestShapes.VALIDATOR.validate(TestShapes.star(rnd, cx, cy, r, n, rnd.nextBoolean()));
            zones.add(new ZoneVersion("t1", String.format("Z%05d", i), 1, "z" + i, ZoneType.TARIFF, rnd.nextInt(5),
                    p, false, 1, "test", "test", Instant.EPOCH));
        }
        // one huge zone to exercise the oversized path
        Polygon city = TestShapes.polygon(TestShapes.rect(77.0, 12.5, 78.0, 13.5));
        zones.add(new ZoneVersion("t1", "CITY", 1, "city", ZoneType.CITY_LIMIT, 0, city, false, 1, "t", "t", Instant.EPOCH));
        return zones;
    }

    @Test
    void gridGivesExactlyTheSameAnswersAsBruteForce() {
        Random rnd = new Random(42);
        List<ZoneVersion> zones = randomZones(rnd, 400);
        ZoneSnapshot grid = new GridSnapshotBuilder(0.01, 256).build("t1", 1, zones);

        List<BoundingBox> boxes = new ArrayList<>();
        for (ZoneVersion z : grid.zones()) {
            boxes.add(z.polygon().bbox());
        }
        ZoneSnapshot brute = new ZoneSnapshot("t1", 1, grid.zones(), new BruteForceIndex(boxes), Instant.EPOCH, 0);

        for (int i = 0; i < 50_000; i++) {
            double lat = 12.75 + rnd.nextDouble() * 0.5;
            double lng = 77.35 + rnd.nextDouble() * 0.5;
            assertEquals(ids(brute.query(lat, lng)), ids(grid.query(lat, lng)), "point " + lat + "," + lng);
        }
    }

    @Test
    void pointsOnCellBordersAndPolygonEdgesAreFound() {
        // zone edge lies exactly on a grid line (77.50 with 0.01 cells)
        Polygon p = TestShapes.polygon(TestShapes.rect(77.50, 12.90, 77.53, 12.93));
        ZoneSnapshot snap = new GridSnapshotBuilder(0.01, 256)
                .build("t1", 1, List.of(TestShapes.zone("EDGE", ZoneType.TARIFF, 1, p)));
        assertEquals(1, snap.query(12.91, 77.50).size());
        assertEquals(1, snap.query(12.93, 77.53).size());
        assertEquals(0, snap.query(12.91, 77.4999999).size());
    }

    @Test
    void oversizedZonesAreStillFound() {
        Polygon big = TestShapes.polygon(TestShapes.rect(70, 8, 90, 30));
        ZoneSnapshot snap = new GridSnapshotBuilder(0.01, 100)
                .build("t1", 1, List.of(TestShapes.zone("BIG", ZoneType.CITY_LIMIT, 0, big)));
        GridIndex index = (GridIndex) snap.index();
        assertEquals(0, index.cellCount());
        assertEquals(1, snap.query(12.97, 77.59).size());
        assertTrue(index.describe().contains("oversized=1"));
    }

    @Test
    void resultsAreOrderedByPriorityThenId() {
        Polygon a = TestShapes.polygon(TestShapes.rect(0, 0, 2, 2));
        Polygon b = TestShapes.polygon(TestShapes.rect(1, 1, 3, 3));
        ZoneSnapshot snap = new GridSnapshotBuilder(0.5, 256).build("t1", 1, List.of(
                TestShapes.zone("LOW", ZoneType.TARIFF, 1, a),
                TestShapes.zone("HIGH", ZoneType.AIRPORT, 9, b)));
        List<ZoneVersion> result = snap.query(1.5, 1.5);
        assertEquals("HIGH", result.get(0).zoneId());
        assertEquals("LOW", result.get(1).zoneId());
    }

    private static List<String> ids(List<ZoneVersion> zones) {
        List<String> out = new ArrayList<>();
        for (ZoneVersion z : zones) {
            out.add(z.zoneId());
        }
        return out;
    }
}
