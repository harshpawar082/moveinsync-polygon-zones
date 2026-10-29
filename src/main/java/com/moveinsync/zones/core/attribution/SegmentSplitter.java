package com.moveinsync.zones.core.attribution;

import com.moveinsync.zones.core.geo.BoundingBox;
import com.moveinsync.zones.core.geo.DoubleList;
import com.moveinsync.zones.core.geo.GeoPoint;
import com.moveinsync.zones.core.geo.GeometryMath;
import com.moveinsync.zones.core.geo.Ring;
import com.moveinsync.zones.core.index.ZoneSnapshot;
import com.moveinsync.zones.core.zone.ZoneVersion;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Splits one trip segment (ping a -> ping b) exactly where it crosses zone
 * boundaries.
 *
 * Steps:
 * 1. get candidate zones whose bounding box touches the segment (spatial index);
 * 2. intersect the segment with every edge of every candidate ring and collect
 *    the crossing fractions t;
 * 3. sort the t values; between two neighbouring t values the segment cannot
 *    cross any edge, so it is entirely inside or outside each zone. Test the
 *    MIDPOINT of each piece to decide which.
 *
 * Testing midpoints (instead of trusting "in/out" flips at each crossing) makes
 * touching a vertex, running along an edge, or two zones sharing an edge all
 * come out right without special cases.
 */
public final class SegmentSplitter {

    private static final double MIN_PIECE = 1e-12;

    private SegmentSplitter() {
    }

    public static List<Piece> split(GeoPoint a, GeoPoint b, ZoneSnapshot snapshot, Predicate<ZoneVersion> filter) {
        if (a.lat() == b.lat() && a.lng() == b.lng()) {
            List<ZoneVersion> here = new ArrayList<>();
            for (ZoneVersion z : snapshot.query(a.lat(), a.lng())) {
                if (filter.test(z)) {
                    here.add(z);
                }
            }
            here.sort(java.util.Comparator.comparing(ZoneVersion::zoneId));
            return List.of(new Piece(0.0, 1.0, here));
        }

        double tol = GeometryMath.BOUNDARY_TOLERANCE_DEG;
        BoundingBox box = BoundingBox.ofSegment(a.x(), a.y(), b.x(), b.y()).expand(tol);
        List<ZoneVersion> candidates = new ArrayList<>();
        for (ZoneVersion z : snapshot.candidatesIn(box)) {
            if (filter.test(z)) {
                candidates.add(z);
            }
        }
        candidates.sort(java.util.Comparator.comparing(ZoneVersion::zoneId));
        if (candidates.isEmpty()) {
            return List.of(new Piece(0.0, 1.0, List.of()));
        }

        DoubleList ts = new DoubleList(16);
        ts.add(0.0);
        ts.add(1.0);
        for (ZoneVersion zone : candidates) {
            for (Ring ring : zone.polygon().rings()) {
                if (!ring.bbox().intersects(box)) {
                    continue;
                }
                addCrossings(a, b, box, ring, ts);
            }
        }
        ts.sort();

        // unique, strictly increasing fractions from exactly 0.0 to exactly 1.0
        DoubleList cuts = new DoubleList(ts.size());
        cuts.add(0.0);
        for (int i = 0; i < ts.size(); i++) {
            double t = ts.get(i);
            if (t - cuts.get(cuts.size() - 1) > MIN_PIECE) {
                cuts.add(t);
            }
        }
        List<Piece> pieces = new ArrayList<>();
        for (int i = 0; i + 1 < cuts.size(); i++) {
            double t0 = cuts.get(i);
            double t1 = cuts.get(i + 1);
            if (i + 2 == cuts.size()) {
                t1 = 1.0; // the last cut may be 1 - 1e-13; snap it
            }
            GeoPoint mid = GeoPoint.lerp(a, b, (t0 + t1) / 2.0);
            List<ZoneVersion> inside = new ArrayList<>();
            for (ZoneVersion zone : candidates) {
                if (zone.polygon().contains(mid)) {
                    inside.add(zone);
                }
            }
            addMerged(pieces, new Piece(t0, t1, inside));
        }
        if (pieces.isEmpty()) {
            pieces.add(new Piece(0.0, 1.0, List.of()));
        }
        return pieces;
    }

    private static void addCrossings(GeoPoint a, GeoPoint b, BoundingBox box, Ring ring, DoubleList out) {
        int n = ring.size();
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double ex0 = ring.x(i);
            double ey0 = ring.y(i);
            double ex1 = ring.x(j);
            double ey1 = ring.y(j);
            if (Math.max(ex0, ex1) < box.minX() || Math.min(ex0, ex1) > box.maxX()
                    || Math.max(ey0, ey1) < box.minY() || Math.min(ey0, ey1) > box.maxY()) {
                continue;
            }
            GeometryMath.intersectionParams(a.x(), a.y(), b.x(), b.y(), ex0, ey0, ex1, ey1, out);
        }
    }

    /** Joins neighbouring pieces that are inside the same zones. */
    private static void addMerged(List<Piece> pieces, Piece next) {
        if (!pieces.isEmpty()) {
            Piece last = pieces.get(pieces.size() - 1);
            if (sameZones(last.zones(), next.zones())) {
                pieces.set(pieces.size() - 1, new Piece(last.t0(), next.t1(), last.zones()));
                return;
            }
        }
        pieces.add(next);
    }

    private static boolean sameZones(List<ZoneVersion> x, List<ZoneVersion> y) {
        if (x.size() != y.size()) {
            return false;
        }
        for (int i = 0; i < x.size(); i++) {
            if (!x.get(i).zoneId().equals(y.get(i).zoneId())) {
                return false;
            }
        }
        return true;
    }
}
