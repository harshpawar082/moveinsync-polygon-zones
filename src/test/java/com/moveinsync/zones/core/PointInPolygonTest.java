package com.moveinsync.zones.core;

import com.moveinsync.zones.core.geo.Location;
import com.moveinsync.zones.core.geo.Polygon;
import org.junit.jupiter.api.Test;

import static com.moveinsync.zones.core.TestShapes.polygon;
import static com.moveinsync.zones.core.TestShapes.rect;
import static com.moveinsync.zones.core.TestShapes.ring;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PointInPolygonTest {

    private final Polygon square = polygon(rect(77.0, 12.0, 77.1, 12.1));

    /** A "U": the notch between the arms is OUTSIDE (non-convex case). */
    private final Polygon uShape = polygon(ring(
            0, 0, 3, 0, 3, 3, 2, 3, 2, 1, 1, 1, 1, 3, 0, 3));

    /** 10x10 square with a 2..4 square hole. */
    private final Polygon withHole = polygon(rect(0, 0, 10, 10), rect(2, 2, 4, 4));

    @Test
    void insideAndOutsideSquare() {
        assertEquals(Location.INSIDE, square.locate(77.05, 12.05));
        assertEquals(Location.OUTSIDE, square.locate(77.2, 12.05));
        assertEquals(Location.OUTSIDE, square.locate(77.05, 11.99));
    }

    @Test
    void pointOnEdgeIsBoundaryAndCountsAsInside() {
        assertEquals(Location.BOUNDARY, square.locate(77.05, 12.0));
        assertEquals(Location.BOUNDARY, square.locate(77.1, 12.07));
        assertTrue(square.contains(77.05, 12.0));
    }

    @Test
    void pointOnVertexIsBoundary() {
        assertEquals(Location.BOUNDARY, square.locate(77.0, 12.0));
        assertEquals(Location.BOUNDARY, square.locate(77.1, 12.1));
    }

    @Test
    void toleranceIsExplicit() {
        // 1e-10 deg (~0.01 mm) outside the edge: still boundary
        assertEquals(Location.BOUNDARY, square.locate(77.1 + 1e-10, 12.05));
        // 1e-6 deg (~11 cm) outside: outside
        assertEquals(Location.OUTSIDE, square.locate(77.1 + 1e-6, 12.05));
        // 1e-6 deg inside: inside
        assertEquals(Location.INSIDE, square.locate(77.1 - 1e-6, 12.05));
    }

    @Test
    void nonConvexNotchIsOutside() {
        assertEquals(Location.OUTSIDE, uShape.locate(1.5, 2.0));
        assertEquals(Location.INSIDE, uShape.locate(0.5, 2.0));
        assertEquals(Location.INSIDE, uShape.locate(2.5, 2.0));
        assertEquals(Location.INSIDE, uShape.locate(1.5, 0.5));
    }

    @Test
    void rayThroughVerticesIsCountedOnce() {
        // y = 1 passes exactly through the inner vertices (1,1) and (2,1) of the U
        assertEquals(Location.INSIDE, uShape.locate(0.5, 1.0 + 1e-7));
        assertEquals(Location.INSIDE, uShape.locate(0.5, 0.999));
        // diamond: the ray from the point hits the right vertex exactly
        Polygon diamond = polygon(ring(0, -1, 1, 0, 0, 1, -1, 0));
        assertEquals(Location.INSIDE, diamond.locate(0.0, 0.0));
        assertEquals(Location.OUTSIDE, diamond.locate(-2.0, 0.0));
        assertEquals(Location.OUTSIDE, diamond.locate(2.0, 0.0));
    }

    @Test
    void holesAreOutsideButHoleEdgesAreBoundary() {
        assertEquals(Location.INSIDE, withHole.locate(1, 1));
        assertEquals(Location.OUTSIDE, withHole.locate(3, 3));
        assertEquals(Location.BOUNDARY, withHole.locate(2, 3));
        assertEquals(Location.BOUNDARY, withHole.locate(4, 4));
        assertEquals(Location.INSIDE, withHole.locate(5, 5));
        assertFalse(withHole.contains(3, 3));
        assertTrue(withHole.contains(2, 3));
    }

    @Test
    void bandIndexGivesTheSameAnswerAsTestingEveryEdge() {
        java.util.Random rnd = new java.util.Random(9);
        for (int shape = 0; shape < 20; shape++) {
            java.util.List<java.util.List<double[]>> coords = TestShapes.star(rnd, 77.6, 12.97, 0.05, 40 + rnd.nextInt(4000), true);
            Polygon p = TestShapes.VALIDATOR.validate(coords);
            for (int i = 0; i < 5000; i++) {
                double x = 77.54 + rnd.nextDouble() * 0.12;
                double y = 12.91 + rnd.nextDouble() * 0.12;
                if (i % 50 == 0) { // exactly on a vertex
                    double[] v = coords.get(0).get(rnd.nextInt(coords.get(0).size()));
                    x = v[0];
                    y = v[1];
                }
                assertEquals(naive(coords, x, y), p.locate(x, y), "shape " + shape + " point " + x + "," + y);
            }
        }
    }

    /** Reference: plain O(V) test over every edge of every ring, no index. */
    private static Location naive(java.util.List<java.util.List<double[]>> rings, double x, double y) {
        Location outer = naiveRing(rings.get(0), x, y);
        if (outer != Location.INSIDE) {
            return outer;
        }
        for (int h = 1; h < rings.size(); h++) {
            Location inHole = naiveRing(rings.get(h), x, y);
            if (inHole == Location.BOUNDARY) {
                return Location.BOUNDARY;
            }
            if (inHole == Location.INSIDE) {
                return Location.OUTSIDE;
            }
        }
        return Location.INSIDE;
    }

    private static Location naiveRing(java.util.List<double[]> ring, double x, double y) {
        boolean inside = false;
        for (int i = 0; i + 1 < ring.size(); i++) {
            double[] a = ring.get(i);
            double[] b = ring.get(i + 1);
            if (com.moveinsync.zones.core.geo.GeometryMath.onSegment(x, y, a[0], a[1], b[0], b[1],
                    com.moveinsync.zones.core.geo.GeometryMath.BOUNDARY_TOLERANCE_DEG)) {
                return Location.BOUNDARY;
            }
            if ((a[1] > y) != (b[1] > y) && x < a[0] + (y - a[1]) * (b[0] - a[0]) / (b[1] - a[1])) {
                inside = !inside;
            }
        }
        return inside ? Location.INSIDE : Location.OUTSIDE;
    }

    @Test
    void horizontalEdgeOnTheRayLine() {
        // y = 3 is the top edge of the U arms; points on it are boundary, points beside it outside
        assertEquals(Location.BOUNDARY, uShape.locate(0.5, 3.0));
        assertEquals(Location.OUTSIDE, uShape.locate(1.5, 3.0));
        assertEquals(Location.OUTSIDE, uShape.locate(-0.5, 3.0));
    }
}
