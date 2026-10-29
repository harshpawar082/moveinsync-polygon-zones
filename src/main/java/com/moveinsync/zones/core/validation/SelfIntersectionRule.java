package com.moveinsync.zones.core.validation;

import com.moveinsync.zones.core.geo.GeometryMath;
import com.moveinsync.zones.core.geo.Ring;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * No edge may cross or touch another edge, within a ring or between rings
 * (so holes can neither cross the outer ring nor touch it).
 *
 * Algorithm: sort-and-sweep on x. Edges are sorted by their left end; while
 * sweeping we keep only the "active" edges whose x-range still overlaps, and
 * test just those pairs. Typical cost O(n log n + k) for n edges and k close
 * pairs; worst case O(n^2) for shapes where most edges overlap in x.
 */
public final class SelfIntersectionRule implements PolygonRule {

    private static final int MAX_REPORTED = 10;

    private record Edge(int ring, int index, int ringSize,
                        double ax, double ay, double bx, double by,
                        double minX, double maxX, double minY, double maxY) {
    }

    @Override
    public Phase phase() {
        return Phase.TOPOLOGY;
    }

    @Override
    public void check(PolygonDraft draft, List<ValidationError> errors) {
        List<Edge> edges = collectEdges(draft.rings());
        edges.sort(Comparator.comparingDouble(Edge::minX));

        double tol = GeometryMath.BOUNDARY_TOLERANCE_DEG;
        List<Edge> active = new ArrayList<>();
        int reported = 0;

        for (Edge edge : edges) {
            // Drop edges that end before this one starts; they cannot meet anything further right.
            List<Edge> stillActive = new ArrayList<>(active.size() + 1);
            for (Edge other : active) {
                if (other.maxX() >= edge.minX() - tol) {
                    stillActive.add(other);
                }
            }
            active = stillActive;

            for (Edge other : active) {
                if (other.maxY() < edge.minY() - tol || other.minY() > edge.maxY() + tol) {
                    continue;
                }
                boolean bad;
                if (isAdjacent(edge, other)) {
                    bad = isSpike(edge, other);
                } else {
                    bad = GeometryMath.segmentsIntersect(edge.ax(), edge.ay(), edge.bx(), edge.by(),
                            other.ax(), other.ay(), other.bx(), other.by());
                }
                if (bad) {
                    errors.add(describe(edge, other));
                    reported++;
                    if (reported >= MAX_REPORTED) {
                        return;
                    }
                }
            }
            active.add(edge);
        }
    }

    private static List<Edge> collectEdges(List<Ring> rings) {
        List<Edge> edges = new ArrayList<>();
        for (int r = 0; r < rings.size(); r++) {
            Ring ring = rings.get(r);
            int n = ring.size();
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                double ax = ring.x(i);
                double ay = ring.y(i);
                double bx = ring.x(j);
                double by = ring.y(j);
                edges.add(new Edge(r, i, n, ax, ay, bx, by,
                        Math.min(ax, bx), Math.max(ax, bx), Math.min(ay, by), Math.max(ay, by)));
            }
        }
        return edges;
    }

    /** Edges i and i+1 of the same ring share a vertex by design. */
    private static boolean isAdjacent(Edge e1, Edge e2) {
        if (e1.ring() != e2.ring()) {
            return false;
        }
        int n = e1.ringSize();
        return (e1.index() + 1) % n == e2.index() || (e2.index() + 1) % n == e1.index();
    }

    /**
     * Adjacent edges may only meet at their shared vertex. If the far end of one
     * lies on the other, the ring folds back on itself (a "spike").
     */
    private static boolean isSpike(Edge e1, Edge e2) {
        double tol = GeometryMath.BOUNDARY_TOLERANCE_DEG;
        Edge first = e1;
        Edge second = e2;
        if ((e2.index() + 1) % e2.ringSize() == e1.index()) {
            first = e2;
            second = e1;
        }
        // first = a -> b, second = b -> c
        boolean cOnFirst = GeometryMath.onSegment(second.bx(), second.by(),
                first.ax(), first.ay(), first.bx(), first.by(), tol);
        boolean aOnSecond = GeometryMath.onSegment(first.ax(), first.ay(),
                second.ax(), second.ay(), second.bx(), second.by(), tol);
        return cOnFirst || aOnSecond;
    }

    private static ValidationError describe(Edge e1, Edge e2) {
        if (e1.ring() == e2.ring()) {
            return new ValidationError("SELF_INTERSECTION",
                    "ring " + e1.ring() + ": edges " + Math.min(e1.index(), e2.index())
                            + " and " + Math.max(e1.index(), e2.index()) + " cross or touch");
        }
        return new ValidationError("RINGS_INTERSECT",
                "ring " + e1.ring() + " edge " + e1.index() + " meets ring " + e2.ring() + " edge " + e2.index());
    }
}
