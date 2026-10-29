package com.moveinsync.zones.core.geo;

/**
 * Small, well-tested geometry primitives. All coordinates are degrees,
 * x = longitude, y = latitude, and edges are straight lines in lat/lng space.
 */
public final class GeometryMath {

    /**
     * A point closer than this to an edge or vertex counts as ON the boundary.
     * 1e-9 degrees is about 0.1 mm: far below GPS accuracy (metres) and far
     * above double rounding error at these magnitudes (~1e-14 degrees).
     */
    public static final double BOUNDARY_TOLERANCE_DEG = 1e-9;

    private GeometryMath() {
    }

    /** z-component of (b - a) x (c - a). Positive when a, b, c turn left. */
    public static double cross(double ax, double ay, double bx, double by, double cx, double cy) {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    }

    /** True when point p is within {@code tolerance} of segment a-b (endpoints included). */
    public static boolean onSegment(double px, double py, double ax, double ay, double bx, double by,
                                    double tolerance) {
        double dx = bx - ax;
        double dy = by - ay;
        double len2 = dx * dx + dy * dy;
        double t = 0.0;
        if (len2 > 0.0) {
            t = ((px - ax) * dx + (py - ay) * dy) / len2;
            if (t < 0.0) {
                t = 0.0;
            } else if (t > 1.0) {
                t = 1.0;
            }
        }
        double cx = ax + t * dx;
        double cy = ay + t * dy;
        double ex = px - cx;
        double ey = py - cy;
        return ex * ex + ey * ey <= tolerance * tolerance;
    }

    /**
     * True when closed segments a-b and c-d share at least one point
     * (crossing, touching, or collinear overlap), using the boundary tolerance.
     */
    public static boolean segmentsIntersect(double ax, double ay, double bx, double by,
                                            double cx, double cy, double dx, double dy) {
        double tol = BOUNDARY_TOLERANCE_DEG;
        if (onSegment(cx, cy, ax, ay, bx, by, tol) || onSegment(dx, dy, ax, ay, bx, by, tol)
                || onSegment(ax, ay, cx, cy, dx, dy, tol) || onSegment(bx, by, cx, cy, dx, dy, tol)) {
            return true;
        }
        double o1 = cross(ax, ay, bx, by, cx, cy);
        double o2 = cross(ax, ay, bx, by, dx, dy);
        double o3 = cross(cx, cy, dx, dy, ax, ay);
        double o4 = cross(cx, cy, dx, dy, bx, by);
        boolean abSplitsCd = (o1 > 0 && o2 < 0) || (o1 < 0 && o2 > 0);
        boolean cdSplitsAb = (o3 > 0 && o4 < 0) || (o3 < 0 && o4 > 0);
        return abSplitsCd && cdSplitsAb;
    }

    /**
     * Adds to {@code out} every parameter t in [0, 1] at which segment p->q meets
     * edge a-b. For a collinear overlap both overlap ends are added. The caller
     * later classifies the midpoint of each sub-interval, so an extra t is
     * harmless; a missing t would be a bug.
     */
    public static void intersectionParams(double px, double py, double qx, double qy,
                                          double ax, double ay, double bx, double by,
                                          DoubleList out) {
        double rx = qx - px;
        double ry = qy - py;
        double sx = bx - ax;
        double sy = by - ay;
        double denom = rx * sy - ry * sx;
        double apx = ax - px;
        double apy = ay - py;
        double rLen2 = rx * rx + ry * ry;
        if (rLen2 == 0.0) {
            return;
        }
        double sLen = Math.sqrt(sx * sx + sy * sy);
        double rLen = Math.sqrt(rLen2);

        if (Math.abs(denom) > 1e-12 * rLen * sLen) {
            double t = (apx * sy - apy * sx) / denom;
            double u = (apx * ry - apy * rx) / denom;
            double tTol = BOUNDARY_TOLERANCE_DEG / rLen;
            double uTol = sLen > 0 ? BOUNDARY_TOLERANCE_DEG / sLen : 0.0;
            if (t >= -tTol && t <= 1 + tTol && u >= -uTol && u <= 1 + uTol) {
                out.add(clamp01(t));
            }
            return;
        }
        // Parallel. Only collinear edges matter: add the overlap end points.
        double distToLine = Math.abs(apx * ry - apy * rx) / rLen;
        if (distToLine > BOUNDARY_TOLERANCE_DEG) {
            return;
        }
        double tA = (apx * rx + apy * ry) / rLen2;
        double tB = ((bx - px) * rx + (by - py) * ry) / rLen2;
        if (tA >= 0 && tA <= 1) {
            out.add(tA);
        }
        if (tB >= 0 && tB <= 1) {
            out.add(tB);
        }
    }

    private static double clamp01(double t) {
        if (t < 0) {
            return 0;
        }
        if (t > 1) {
            return 1;
        }
        return t;
    }
}
