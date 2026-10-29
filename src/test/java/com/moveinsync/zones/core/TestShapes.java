package com.moveinsync.zones.core;

import com.moveinsync.zones.core.geo.Polygon;
import com.moveinsync.zones.core.validation.PolygonValidator;
import com.moveinsync.zones.core.zone.ZoneType;
import com.moveinsync.zones.core.zone.ZoneVersion;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Helpers to build test polygons. Positions are [lng, lat] like GeoJSON. */
public final class TestShapes {

    private TestShapes() {
    }

    public static final PolygonValidator VALIDATOR = PolygonValidator.standard(200_000);

    /** Closed ring from x,y pairs (the closing point is added automatically). */
    public static List<double[]> ring(double... xy) {
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) {
            out.add(new double[]{xy[i], xy[i + 1]});
        }
        out.add(new double[]{xy[0], xy[1]});
        return out;
    }

    public static List<double[]> rect(double minX, double minY, double maxX, double maxY) {
        return ring(minX, minY, maxX, minY, maxX, maxY, minX, maxY);
    }

    @SafeVarargs
    public static List<List<double[]>> coords(List<double[]>... rings) {
        List<List<double[]>> out = new ArrayList<>();
        for (List<double[]> r : rings) {
            out.add(r);
        }
        return out;
    }

    @SafeVarargs
    public static Polygon polygon(List<double[]>... rings) {
        return VALIDATOR.validate(coords(rings));
    }

    public static ZoneVersion zone(String id, ZoneType type, int priority, Polygon polygon) {
        return new ZoneVersion("t1", id, 1, id, type, priority, polygon, false, 1, "test", "test", Instant.EPOCH);
    }

    /**
     * A random star-shaped (non-convex) polygon: angles strictly increase and
     * each vertex radius is in [0.6r, r], so the ring never self-intersects.
     * Optionally with one hole of radius < 0.3r, always inside the outer ring.
     */
    public static List<List<double[]>> star(Random rnd, double cx, double cy, double r, int vertices, boolean withHole) {
        List<List<double[]>> rings = new ArrayList<>();
        rings.add(starRing(rnd, cx, cy, r, vertices, false));
        if (withHole) {
            rings.add(starRing(rnd, cx, cy, r * 0.3, Math.max(8, vertices / 8), true));
        }
        return rings;
    }

    private static List<double[]> starRing(Random rnd, double cx, double cy, double r, int n, boolean clockwise) {
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double angle = 2 * Math.PI * i / n;
            if (clockwise) {
                angle = -angle;
            }
            double radius = r * (0.6 + 0.4 * rnd.nextDouble());
            pts.add(new double[]{cx + radius * Math.cos(angle), cy + radius * Math.sin(angle)});
        }
        pts.add(pts.get(0));
        return pts;
    }
}
