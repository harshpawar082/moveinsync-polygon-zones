package com.moveinsync.zones.core.geo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A polygon with one outer ring and zero or more holes. Immutable.
 *
 * Boundary rule: a polygon is treated as a CLOSED set. A point on the outer
 * edge, on a vertex, or on the edge of a hole is INSIDE for {@link #contains}.
 * Points strictly inside a hole are OUTSIDE.
 */
public final class Polygon {

    private final Ring outer;
    private final List<Ring> holes;

    public Polygon(Ring outer, List<Ring> holes) {
        this.outer = outer;
        this.holes = Collections.unmodifiableList(new ArrayList<>(holes));
    }

    /** Builds from GeoJSON Polygon coordinates: [outerRing, hole1, hole2, ...]. Does not validate. */
    public static Polygon fromCoordinates(List<List<double[]>> coordinates) {
        Ring outer = Ring.fromPositions(coordinates.get(0));
        List<Ring> holes = new ArrayList<>();
        for (int i = 1; i < coordinates.size(); i++) {
            holes.add(Ring.fromPositions(coordinates.get(i)));
        }
        return new Polygon(outer, holes);
    }

    public Ring outer() {
        return outer;
    }

    public List<Ring> holes() {
        return holes;
    }

    public BoundingBox bbox() {
        return outer.bbox();
    }

    public int vertexCount() {
        int count = outer.size();
        for (Ring hole : holes) {
            count += hole.size();
        }
        return count;
    }

    public Location locate(double x, double y) {
        Location inOuter = outer.locate(x, y);
        if (inOuter != Location.INSIDE) {
            return inOuter;
        }
        for (Ring hole : holes) {
            Location inHole = hole.locate(x, y);
            if (inHole == Location.BOUNDARY) {
                return Location.BOUNDARY;
            }
            if (inHole == Location.INSIDE) {
                return Location.OUTSIDE;
            }
        }
        return Location.INSIDE;
    }

    public boolean contains(double x, double y) {
        return locate(x, y) != Location.OUTSIDE;
    }

    public boolean contains(GeoPoint p) {
        return contains(p.x(), p.y());
    }

    /** All rings, outer first. */
    public List<Ring> rings() {
        List<Ring> all = new ArrayList<>(holes.size() + 1);
        all.add(outer);
        all.addAll(holes);
        return all;
    }

    public List<List<double[]>> toCoordinates() {
        List<List<double[]>> out = new ArrayList<>();
        for (Ring ring : rings()) {
            out.add(ring.toPositions());
        }
        return out;
    }
}
