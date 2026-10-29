package com.moveinsync.zones.core.attribution;

import com.moveinsync.zones.core.geo.GeoPoint;
import com.moveinsync.zones.core.geo.Haversine;
import com.moveinsync.zones.core.index.ZoneSnapshot;
import com.moveinsync.zones.core.zone.ZoneType;
import com.moveinsync.zones.core.zone.ZoneVersion;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Follows one trip ping by ping: splits each segment at zone boundaries,
 * adds the metres to the right tariff zone, and emits entry/exit/no-go events.
 *
 * Version rule (see README "Trip consistency"):
 * - billing zones (tariff, airport, city) come from the snapshot PINNED when the
 *   trip started, so the fare of a running trip never changes under it;
 * - no-go zones come from the LATEST snapshot on every ping, because a safety
 *   rule must apply the moment an admin publishes it.
 *
 * Not thread-safe on its own; the owner (TripService) serialises calls per trip.
 */
public final class TripTracker {

    private static final Predicate<ZoneVersion> BILLING_SIDE = z -> !z.type().safetyZone();
    private static final Predicate<ZoneVersion> SAFETY_SIDE = z -> z.type().safetyZone();
    private static final int MAX_EVENTS = 10_000;

    public enum Outcome {
        ACCEPTED,
        DUPLICATE,
        OUT_OF_ORDER,
        INVALID
    }

    public record FeedResult(Outcome outcome, List<ZoneEvent> events) {
    }

    private final String tripId;
    private final ZoneSnapshot billingSnapshot;
    private final Supplier<ZoneSnapshot> safetySnapshot;
    private final NightWindow nightWindow;

    private Ping last;
    private double totalMeters;
    private final Map<String, Double> metersByZone = new TreeMap<>();
    private final Map<String, ZoneVersion> zoneInfo = new LinkedHashMap<>();
    private final Map<String, Membership> inside = new LinkedHashMap<>();
    private final List<ZoneEvent> events = new ArrayList<>();
    private int accepted;
    private int duplicates;
    private int outOfOrder;
    private int invalid;
    private boolean closed;
    private long lastSafetyVersion = -1;

    private record Membership(ZoneVersion zone, long enteredAt) {
    }

    public TripTracker(String tripId, ZoneSnapshot billingSnapshot, Supplier<ZoneSnapshot> safetySnapshot,
                       NightWindow nightWindow) {
        this.tripId = tripId;
        this.billingSnapshot = billingSnapshot;
        this.safetySnapshot = safetySnapshot;
        this.nightWindow = nightWindow;
    }

    public FeedResult feed(Ping ping) {
        if (closed) {
            throw new IllegalStateException("trip " + tripId + " is closed");
        }
        if (!GeoPoint.isValid(ping.lat(), ping.lng())) {
            invalid++;
            return new FeedResult(Outcome.INVALID, List.of());
        }
        if (last != null && ping.epochMillis() <= last.epochMillis()) {
            if (ping.equals(last) || ping.epochMillis() == last.epochMillis()) {
                duplicates++;
                return new FeedResult(Outcome.DUPLICATE, List.of());
            }
            outOfOrder++;
            return new FeedResult(Outcome.OUT_OF_ORDER, List.of());
        }
        int before = events.size();
        if (last == null) {
            List<ZoneVersion> here = zonesAt(ping.point());
            updateMembership(here, ping.epochMillis(), ping.point(), "CROSSED_BOUNDARY");
        } else {
            processSegment(last, ping);
        }
        last = ping;
        accepted++;
        return new FeedResult(Outcome.ACCEPTED, new ArrayList<>(events.subList(before, events.size())));
    }

    private void processSegment(Ping a, Ping b) {
        GeoPoint pa = a.point();
        GeoPoint pb = b.point();
        List<Piece> billing = SegmentSplitter.split(pa, pb, billingSnapshot, BILLING_SIDE);
        ZoneSnapshot safetyNow = safetySnapshot.get();
        boolean safetyChanged = lastSafetyVersion >= 0 && safetyNow.catalogVersion() != lastSafetyVersion;
        lastSafetyVersion = safetyNow.catalogVersion();
        List<Piece> safety = SegmentSplitter.split(pa, pb, safetyNow, SAFETY_SIDE);
        List<Piece> pieces = merge(billing, safety);

        long dt = b.epochMillis() - a.epochMillis();
        for (Piece piece : pieces) {
            GeoPoint start = GeoPoint.lerp(pa, pb, piece.t0());
            GeoPoint end = GeoPoint.lerp(pa, pb, piece.t1());
            long at = a.epochMillis() + Math.round(piece.t0() * dt);
            String cause = "CROSSED_BOUNDARY";
            if (piece.t0() == 0.0 && safetyChanged) {
                cause = "ZONE_CHANGED"; // a no-go zone was edited between the two pings
            }
            updateMembership(piece.zones(), at, start, cause);

            double meters = Haversine.distanceMeters(start, end);
            totalMeters += meters;
            String key = billingWinner(piece.zones());
            Double sofar = metersByZone.get(key);
            if (sofar == null) {
                sofar = 0.0;
            }
            metersByZone.put(key, sofar + meters);
        }
    }

    /** Zones present at a single point, from the right snapshot for each side. */
    private List<ZoneVersion> zonesAt(GeoPoint p) {
        List<ZoneVersion> out = new ArrayList<>();
        for (ZoneVersion z : billingSnapshot.query(p.lat(), p.lng())) {
            if (BILLING_SIDE.test(z)) {
                out.add(z);
            }
        }
        ZoneSnapshot safetyNow = safetySnapshot.get();
        lastSafetyVersion = safetyNow.catalogVersion();
        for (ZoneVersion z : safetyNow.query(p.lat(), p.lng())) {
            if (SAFETY_SIDE.test(z)) {
                out.add(z);
            }
        }
        return out;
    }

    /** Both lists cover [0, 1] in order; returns the common refinement. */
    private static List<Piece> merge(List<Piece> x, List<Piece> y) {
        List<Piece> out = new ArrayList<>();
        int i = 0;
        int j = 0;
        double t = 0.0;
        while (i < x.size() && j < y.size()) {
            Piece px = x.get(i);
            Piece py = y.get(j);
            double end = Math.min(px.t1(), py.t1());
            List<ZoneVersion> zones = new ArrayList<>(px.zones());
            zones.addAll(py.zones());
            if (end > t) {
                out.add(new Piece(t, end, zones));
            }
            t = end;
            if (px.t1() <= end) {
                i++;
            }
            if (py.t1() <= end) {
                j++;
            }
        }
        return out;
    }

    /** The highest-priority billable zone, ties broken by zoneId; UNZONED if none. */
    private String billingWinner(List<ZoneVersion> zones) {
        ZoneVersion best = null;
        for (ZoneVersion z : zones) {
            if (!z.type().billable()) {
                continue;
            }
            if (best == null || z.priority() > best.priority()
                    || (z.priority() == best.priority() && z.zoneId().compareTo(best.zoneId()) < 0)) {
                best = z;
            }
        }
        if (best == null) {
            return TripSummary.UNZONED;
        }
        zoneInfo.put(best.zoneId(), best);
        return best.zoneId();
    }

    private void updateMembership(List<ZoneVersion> zonesNow, long at, GeoPoint where, String exitCause) {
        // exits first, in the order the zones were entered
        Iterator<Map.Entry<String, Membership>> it = inside.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Membership> e = it.next();
            if (!containsId(zonesNow, e.getKey())) {
                Membership m = e.getValue();
                addEvent(new ZoneEvent(ZoneEvent.Type.EXIT, m.zone().zoneId(), m.zone().name(), m.zone().type(),
                        m.zone().version(), Instant.ofEpochMilli(at), at - m.enteredAt(), exitCause,
                        where.lat(), where.lng()));
                it.remove();
            }
        }
        for (ZoneVersion z : zonesNow) {
            Membership current = inside.get(z.zoneId());
            if (current != null) {
                if (current.zone().version() != z.version()) {
                    inside.put(z.zoneId(), new Membership(z, current.enteredAt()));
                }
                continue;
            }
            inside.put(z.zoneId(), new Membership(z, at));
            addEvent(new ZoneEvent(ZoneEvent.Type.ENTRY, z.zoneId(), z.name(), z.type(), z.version(),
                    Instant.ofEpochMilli(at), null, null, where.lat(), where.lng()));
            if (z.type() == ZoneType.NO_GO && nightWindow.contains(at)) {
                addEvent(new ZoneEvent(ZoneEvent.Type.NO_GO_ALERT, z.zoneId(), z.name(), z.type(), z.version(),
                        Instant.ofEpochMilli(at), null, null, where.lat(), where.lng()));
            }
        }
    }

    private static boolean containsId(List<ZoneVersion> zones, String zoneId) {
        for (ZoneVersion z : zones) {
            if (z.zoneId().equals(zoneId)) {
                return true;
            }
        }
        return false;
    }

    private void addEvent(ZoneEvent event) {
        if (events.size() < MAX_EVENTS) {
            events.add(event);
        }
    }

    /** Ends the trip: every open zone gets an EXIT at the last ping. Idempotent. */
    public TripSummary close() {
        if (!closed && last != null) {
            List<String> open = new ArrayList<>(inside.keySet());
            for (String zoneId : open) {
                Membership m = inside.get(zoneId);
                addEvent(new ZoneEvent(ZoneEvent.Type.EXIT, zoneId, m.zone().name(), m.zone().type(),
                        m.zone().version(), Instant.ofEpochMilli(last.epochMillis()),
                        last.epochMillis() - m.enteredAt(), "TRIP_END", last.lat(), last.lng()));
            }
            inside.clear();
        }
        closed = true;
        return summary();
    }

    public TripSummary summary() {
        Map<String, BigDecimal> km = DistanceRounding.partsInKm(orderedForOutput(), totalMeters);
        List<TripSummary.ZoneDistance> parts = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : km.entrySet()) {
            ZoneVersion info = zoneInfo.get(e.getKey());
            if (info == null) {
                parts.add(new TripSummary.ZoneDistance(e.getKey(), "Outside all tariff zones", null, e.getValue()));
            } else {
                parts.add(new TripSummary.ZoneDistance(info.zoneId(), info.name(), info.type(), e.getValue()));
            }
        }
        List<TripSummary.OpenZone> open = new ArrayList<>();
        for (Membership m : inside.values()) {
            long lastTs = last == null ? m.enteredAt() : last.epochMillis();
            open.add(new TripSummary.OpenZone(m.zone().zoneId(), m.zone().name(), m.zone().type(),
                    Instant.ofEpochMilli(m.enteredAt()), lastTs - m.enteredAt()));
        }
        return new TripSummary(tripId, billingSnapshot.catalogVersion(), DistanceRounding.totalInKm(totalMeters),
                parts, List.copyOf(events), open, accepted, duplicates, outOfOrder, invalid, closed);
    }

    /** Zone ids alphabetically, UNZONED last. */
    private Map<String, Double> orderedForOutput() {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : metersByZone.entrySet()) {
            if (!e.getKey().equals(TripSummary.UNZONED)) {
                out.put(e.getKey(), e.getValue());
            }
        }
        Double unzoned = metersByZone.get(TripSummary.UNZONED);
        if (unzoned != null) {
            out.put(TripSummary.UNZONED, unzoned);
        }
        return out;
    }

    public long billingCatalogVersion() {
        return billingSnapshot.catalogVersion();
    }

    public boolean isClosed() {
        return closed;
    }
}
