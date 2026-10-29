package com.moveinsync.zones.core.geo;

import java.util.ArrayList;
import java.util.List;

/**
 * One closed ring of a polygon, stored WITHOUT the repeated closing vertex.
 * Edge i joins vertex i to vertex (i + 1) % size.
 * Immutable, so it is safe to share between threads.
 */
public final class Ring {

    private final double[] xs;
    private final double[] ys;
    private final BoundingBox bbox;

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
     * left to floating-point luck. O(n) in the number of vertices.
     */
    public Location locate(double px, double py) {
        double tol = GeometryMath.BOUNDARY_TOLERANCE_DEG;
        if (px < bbox.minX() - tol || px > bbox.maxX() + tol || py < bbox.minY() - tol || py > bbox.maxY() + tol) {
            return Location.OUTSIDE;
        }
        boolean inside = false;
        int n = xs.length;
        int j = n - 1;
        for (int i = 0; i < n; i++) {
            double xi = xs[i];
            double yi = ys[i];
            double xj = xs[j];
            double yj = ys[j];

            // Cheap box test before the exact on-edge test.
            if (px >= Math.min(xi, xj) - tol && px <= Math.max(xi, xj) + tol
                    && py >= Math.min(yi, yj) - tol && py <= Math.max(yi, yj) + tol) {
                if (GeometryMath.onSegment(px, py, xj, yj, xi, yi, tol)) {
                    return Location.BOUNDARY;
                }
            }

            // Half-open rule: an edge counts if exactly one end is strictly above py.
            // This handles a ray passing exactly through a vertex.
            if ((yi > py) != (yj > py)) {
                double xCross = xj + (py - yj) * (xi - xj) / (yi - yj);
                if (px < xCross) {
                    inside = !inside;
                }
            }
            j = i;
        }
        if (inside) {
            return Location.INSIDE;
        }
        return Location.OUTSIDE;
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
