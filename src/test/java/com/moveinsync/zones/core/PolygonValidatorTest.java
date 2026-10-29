package com.moveinsync.zones.core;

import com.moveinsync.zones.core.geo.Polygon;
import com.moveinsync.zones.core.validation.InvalidPolygonException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static com.moveinsync.zones.core.TestShapes.VALIDATOR;
import static com.moveinsync.zones.core.TestShapes.coords;
import static com.moveinsync.zones.core.TestShapes.rect;
import static com.moveinsync.zones.core.TestShapes.ring;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolygonValidatorTest {

    private static String firstCode(List<List<double[]>> coordinates) {
        InvalidPolygonException e = assertThrows(InvalidPolygonException.class, () -> VALIDATOR.validate(coordinates));
        return e.errors().get(0).code();
    }

    @Test
    void acceptsPolygonWithHole() {
        Polygon p = VALIDATOR.validate(coords(rect(0, 0, 10, 10), rect(2, 2, 4, 4)));
        assertEquals(1, p.holes().size());
        assertEquals(8, p.vertexCount());
    }

    @Test
    void rejectsRingThatIsNotClosed() {
        List<double[]> open = new ArrayList<>();
        open.add(new double[]{0, 0});
        open.add(new double[]{1, 0});
        open.add(new double[]{1, 1});
        open.add(new double[]{0, 1});
        assertEquals("RING_NOT_CLOSED", firstCode(coords(open)));
    }

    @Test
    void rejectsBowTie() {
        assertEquals("SELF_INTERSECTION", firstCode(coords(ring(0, 0, 2, 2, 2, 0, 0, 2))));
    }

    @Test
    void rejectsSpikeThatFoldsBack() {
        // goes out to (3,0) and comes straight back along the same line
        assertEquals("SELF_INTERSECTION", firstCode(coords(ring(0, 0, 3, 0, 1, 0, 1, 1))));
    }

    @Test
    void rejectsHoleOutsideOuterRing() {
        assertEquals("HOLE_OUTSIDE_OUTER", firstCode(coords(rect(0, 0, 10, 10), rect(20, 20, 22, 22))));
    }

    @Test
    void rejectsHoleCrossingOuterRing() {
        assertEquals("RINGS_INTERSECT", firstCode(coords(rect(0, 0, 10, 10), rect(8, 8, 12, 12))));
    }

    @Test
    void rejectsHoleTouchingOuterRing() {
        assertEquals("RINGS_INTERSECT", firstCode(coords(rect(0, 0, 10, 10), rect(0, 2, 4, 4))));
    }

    @Test
    void rejectsNestedHoles() {
        assertEquals("HOLE_INSIDE_HOLE", firstCode(coords(rect(0, 0, 10, 10), rect(2, 2, 8, 8), rect(3, 3, 4, 4))));
    }

    @Test
    void rejectsTooFewVertices() {
        assertEquals("TOO_FEW_VERTICES", firstCode(coords(ring(0, 0, 1, 1))));
    }

    @Test
    void rejectsOutOfRangeCoordinates() {
        assertEquals("COORDINATE_OUT_OF_RANGE", firstCode(coords(rect(170, 0, 190, 10))));
        assertEquals("COORDINATE_OUT_OF_RANGE", firstCode(coords(ring(0, 0, 1, Double.NaN, 1, 1))));
    }

    @Test
    void rejectsDegenerateShapes() {
        // all vertices on one line: the ring folds back on itself
        assertEquals("SELF_INTERSECTION", firstCode(coords(ring(0, 0, 1, 1, 2, 2))));
        // a triangle of ~0.00006 m^2: technically simple but useless
        assertEquals("ZERO_AREA", firstCode(coords(ring(0, 0, 1e-7, 0, 0, 1e-7))));
    }

    @Test
    void reportsAllStructuralErrorsTogether() {
        List<double[]> open = new ArrayList<>();
        open.add(new double[]{0, 0});
        open.add(new double[]{1, 0});
        open.add(new double[]{1, 1});
        InvalidPolygonException e = assertThrows(InvalidPolygonException.class,
                () -> VALIDATOR.validate(coords(rect(0, 0, 10, 10), open)));
        assertTrue(e.errors().size() >= 1);
        assertEquals("RING_NOT_CLOSED", e.errors().get(0).code());
    }

    @Test
    void validatesLargePolygonQuickly() {
        List<List<double[]>> big = TestShapes.star(new Random(7), 77.6, 12.97, 0.05, 5000, true);
        long start = System.nanoTime();
        Polygon p = VALIDATOR.validate(big);
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertTrue(p.vertexCount() > 5000);
        assertTrue(ms < 2000, "validation of a 5,000-vertex polygon took " + ms + " ms");
    }
}
