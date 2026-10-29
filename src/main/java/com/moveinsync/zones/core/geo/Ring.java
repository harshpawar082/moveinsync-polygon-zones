package com.moveinsync.zones.core.geo;

import java.util.ArrayList;
import java.util.List;

/**
 * One closed ring of a polygon, stored WITHOUT the repeated closing vertex.
 * Edge i joins vertex i to vertex (i + 1) % size.
 * Immutable, so it is safe to share between threads.
 */
public final class Ring {

    /** Rings with at least this many vertices get a band index (small rings are faster without). */
    static final int BAND_THRESHOLD = 32;

    private final double[] xs;
    private final double[] ys;
    private final BoundingBox bbox;

    /*
     * Band index: the ring's height is cut into horizontal bands, and each band
     * lists the edges whose y-range touches it. A horizontal ray at height y can
     * only cross edges that span y, and those are exactly the edges listed in
     * y's band. So point-in-polygon looks at a handful of edges instead of all
     * of them: about O(V / bands + long edges) instead of O(V).
     * Stored compactly (CSR): edges of band b are bandEdges[bandStart[b] .. bandStart[b + 1]).
     */
    private final int bandCount;
    private final double bandMinY;
    private final double bandHeight;
    private final int[] bandStart;
    private final int[] bandEdges;

    private Ring(double[] xs, double[] ys) {
        this.xs = xs;
        this.ys = ys;
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < xs.length; i++) {
            minX = Math.min(minX, xs[i]);
            maxX = Math.max(maxX, xs[i]);
            minY = Math.min(minY, ys[i]);
            maxY = Math.max(maxY, ys[i]);
        }
        this.bbox = new BoundingBox(minX, minY, maxX, maxY);

        int n = xs.length;
        if (n >= BAND_THRESHOLD && maxY > minY) {
            int count = Math.min(4096, Math.max(1, n / 4));
            this.bandCount = count;
            this.bandMinY = minY;
            this.bandHeight = (maxY - minY) / count;
            int[] sizes = new int[count];
            for (int e = 0; e < n; e++) {
                int lo = edgeLowBand(e);
                int hi = edgeHighBand(e);
                for (int b = lo; b <= hi; b++) {
                    sizes[b]++;
                }
            }
            int[] start = new int[count + 1];
            for (int b = 0; b < count; b++) {
                start[b + 1] = start[b] + sizes[b];
            }
            int[] fill = new int[count];
            int[] edges = new int[start[count]];
            for (int e = 0; e < n; e++) {
                int lo = edgeLowBand(e);
                int hi = edgeHighBand(e);
                for (int b = lo; b <= hi; b++) {
                    edges[start[b] + fill[b]] = e;
                    fill[b]++;
                }
            }
            this.bandStart = start;
            this.bandEdges = edges;
        } else {
            this.bandCount = 0;
            this.bandMinY = 0;
            this.bandHeight = 0;
            this.bandStart = null;
            this.bandEdges = null;
        }
    }

    private int bandOf(double y) {
        int b = (int) Math.floor((y - bandMinY) / bandHeight);
        if (b < 0) {
            return 0;
        }
        if (b >= bandCount) {
            return bandCount - 1;
        }
        return b;
    }

    private int edgeLowBand(int e) {
        int f = (e + 1) % xs.length;
        return bandOf(Math.min(ys[e], ys[f]) - GeometryMath.BOUNDARY_TOLERANCE_DEG);
    }

    private int edgeHighBand(int e) {
        int f = (e + 1) % xs.length;
        return bandOf(Math.max(ys[e], ys[f]) + GeometryMath.BOUNDARY_TOLERANCE_DEG);
    }

    /**
     * Builds a ring from GeoJSON positions ([lng, lat]). Drops the closing
     * position and any consecutive duplicate positions. Does not validate.
     */
    public static Ring fromPositions(List<double[]> positions) {
        List<double[]> cleaned = new ArrayList<>();
        for (double[] p : positions) {
            if (!cleaned.isEmpty()) {
                double[] last = cleaned.get(cleaned.size() - 1);
                if (last[0] == p[0] && last[1] == p[1]) {
                    continue;
                }
            }
            cleaned.add(p);
        }
        if (cleaned.size() > 1) {
            double[] first = cleaned.get(0);
            double[] last = cleaned.get(cleaned.size() - 1);
            if (first[0] == last[0] && first[1] == last[1]) {
                cleaned.remove(cleaned.size() - 1);
            }
        }
        double[] xs = new double[cleaned.size()];
        double[] ys = new double[cleaned.size()];
        for (int i = 0; i < cleaned.size(); i++) {
            xs[i] = cleaned.get(i)[0];
            ys[i] = cleaned.get(i)[1];
        }
        return new Ring(xs, ys);
    }

    public int size() {
        return xs.length;
    }

    public double x(int i) {
        return xs[i];
    }

    public double y(int i) {
        return ys[i];
    }

    public BoundingBox bbox() {
        return bbox;
    }

    /** Signed area in square degrees (shoelace). Positive = counter-clockwise. */
    public double signedArea() {
        double sum = 0;
        int n = xs.length;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            sum += xs[i] * ys[j] - xs[j] * ys[i];
        }
        return sum / 2.0;
    }

    /**
     * Ray casting (crossing number) with a half-open rule for vertices, plus an
     * explicit boundary check first, so points on edges/vertices are never
     * left to floating-point luck. Uses the band index when the ring has one.
     */
    public Location locate(double px, double py) {
        double tol = GeometryMath.BOUNDARY_TOLERANCE_DEG;
        if (px < bbox.minX() - tol || px > bbox.maxX() + tol || py < bbox.minY() - tol || py > bbox.maxY() + tol) {
            return Location.OUTSIDE;
        }
        int n = xs.length;
        boolean inside = false;
        if (bandCount > 0) {
            int b = bandOf(py);
            for (int k = bandStart[b]; k < bandStart[b + 1]; k++) {
                int i = bandEdges[k];
                int j = (i + 1 == n) ? 0 : i + 1;
                int r = testEdge(px, py, i, j);
                if (r == ON_EDGE) {
                    return Location.BOUNDARY;
                }
                if (r == CROSSES) {
                    inside = !inside;
                }
            }
        } else {
            int j = n - 1;
            for (int i = 0; i < n; i++) {
                int r = testEdge(px, py, j, i);
                if (r == ON_EDGE) {
                    return Location.BOUNDARY;
                }
                if (r == CROSSES) {
                    inside = !inside;
                }
                j = i;
            }
        }
        if (inside) {
            return Location.INSIDE;
        }
        return Location.OUTSIDE;
    }

    private static final int MISSES = 0;
    private static final int CROSSES = 1;
    private static final int ON_EDGE = 2;

    /** Is p on edge i-j, and does the ray from p to +x cross it? */
    private int testEdge(double px, double py, int i, int j) {
        double tol = GeometryMath.BOUNDARY_TOLERANCE_DEG;
        double xi = xs[i];
        double yi = ys[i];
        double xj = xs[j];
        double yj = ys[j];
        // cheap box test before the exact on-edge test
        if (px >= Math.min(xi, xj) - tol && px <= Math.max(xi, xj) + tol
                && py >= Math.min(yi, yj) - tol && py <= Math.max(yi, yj) + tol) {
            if (GeometryMath.onSegment(px, py, xi, yi, xj, yj, tol)) {
                return ON_EDGE;
            }
        }
        // Half-open rule: an edge counts if exactly one end is strictly above py.
        // This handles a ray passing exactly through a vertex.
        if ((yi > py) != (yj > py)) {
            double xCross = xi + (py - yi) * (xj - xi) / (yj - yi);
            if (px < xCross) {
                return CROSSES;
            }
        }
        return MISSES;
    }

    /**
     * Adds the fractions t at which segment a->b meets an edge of this ring.
     * Only edges in the bands the segment's box spans are tested.
     * (An edge listed in several bands may add the same t twice; callers dedupe.)
     */
    public void collectCrossings(double ax, double ay, double bx, double by, BoundingBox box, DoubleList out) {
        int n = xs.length;
        if (bandCount > 0) {
            int lo = bandOf(box.minY());
            int hi = bandOf(box.maxY());
            if ((hi - lo + 1) * 2 < bandCount) {
                for (int b = lo; b <= hi; b++) {
                    for (int k = bandStart[b]; k < bandStart[b + 1]; k++) {
                        crossEdge(ax, ay, bx, by, box, bandEdges[k], out);
                    }
                }
                return;
            }
        }
        for (int e = 0; e < n; e++) {
            crossEdge(ax, ay, bx, by, box, e, out);
        }
    }

    private void crossEdge(double ax, double ay, double bx, double by, BoundingBox box, int e, DoubleList out) {
        int f = (e + 1 == xs.length) ? 0 : e + 1;
        double ex0 = xs[e];
        double ey0 = ys[e];
        double ex1 = xs[f];
        double ey1 = ys[f];
        if (Math.max(ex0, ex1) < box.minX() || Math.min(ex0, ex1) > box.maxX()
                || Math.max(ey0, ey1) < box.minY() || Math.min(ey0, ey1) > box.maxY()) {
            return;
        }
        GeometryMath.intersectionParams(ax, ay, bx, by, ex0, ey0, ex1, ey1, out);
    }

    /** GeoJSON positions including the closing position. */
    public List<double[]> toPositions() {
        List<double[]> out = new ArrayList<>(xs.length + 1);
        for (int i = 0; i < xs.length; i++) {
            out.add(new double[]{xs[i], ys[i]});
        }
        if (xs.length > 0) {
            out.add(new double[]{xs[0], ys[0]});
        }
        return out;
    }
}
