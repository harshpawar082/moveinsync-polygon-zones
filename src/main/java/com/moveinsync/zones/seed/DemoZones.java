package com.moveinsync.zones.seed;

import com.moveinsync.zones.core.catalog.ZoneCatalogService;
import com.moveinsync.zones.core.geo.Haversine;
import com.moveinsync.zones.core.zone.ZoneType;

import java.util.ArrayList;
import java.util.List;

/**
 * Demo zones around Bengaluru (shapes are illustrative, not real boundaries).
 * The city/airport border is placed exactly 30.4 km north of the demo trip's
 * start, to reproduce the case study's "42 km = 30.4 city + 11.6 airport".
 */
public final class DemoZones {

    /** Degrees of latitude per km on the spherical earth used for distances. */
    public static final double DEG_PER_KM = 180.0 / (Math.PI * Haversine.EARTH_RADIUS_M / 1000.0);
    public static final double TRIP_START_LAT = 12.90;
    public static final double TRIP_LNG = 77.62;
    /** The "toll plaza" latitude where the city zone ends and the airport zone begins. */
    public static final double TOLL_PLAZA_LAT = TRIP_START_LAT + 30.4 * DEG_PER_KM;

    private DemoZones() {
    }

    public static List<ZoneCatalogService.ZoneDraft> acme() {
        double b = TOLL_PLAZA_LAT;
        List<ZoneCatalogService.ZoneDraft> zones = new ArrayList<>();
        // non-convex city zone (notch on the west side) with a hole (cantonment, billed as unzoned)
        zones.add(new ZoneCatalogService.ZoneDraft("BLR_CITY", "Bengaluru city tariff", ZoneType.TARIFF, 1,
                List.of(
                        ring(77.45, 12.80, 77.80, 12.80, 77.80, b, 77.45, b, 77.45, 13.00, 77.50, 12.95, 77.45, 12.90),
                        ring(77.70, 12.95, 77.70, 12.99, 77.74, 12.99, 77.74, 12.95)),
                "demo seed"));
        zones.add(new ZoneCatalogService.ZoneDraft("BLR_AIRPORT", "Kempegowda airport surcharge zone", ZoneType.AIRPORT, 5,
                List.of(ring(77.55, b, 77.75, b, 77.75, 13.30, 77.55, 13.30)), "demo seed"));
        zones.add(new ZoneCatalogService.ZoneDraft("BLR_LIMITS", "Bengaluru city limits", ZoneType.CITY_LIMIT, 0,
                List.of(ring(77.30, 12.70, 77.90, 12.70, 77.90, 13.40, 77.30, 13.40)), "demo seed"));
        zones.add(new ZoneCatalogService.ZoneDraft("QUARRY_NOGO", "Quarry road (no entry at night)", ZoneType.NO_GO, 0,
                List.of(ring(77.64, 13.02, 77.68, 13.02, 77.68, 13.06, 77.64, 13.06)), "demo seed"));
        return zones;
    }

    public static List<ZoneCatalogService.ZoneDraft> globex() {
        List<ZoneCatalogService.ZoneDraft> zones = new ArrayList<>();
        zones.add(new ZoneCatalogService.ZoneDraft("GLOBEX_NORTH", "Globex north tariff", ZoneType.TARIFF, 1,
                List.of(ring(77.55, 13.10, 77.75, 13.10, 77.75, 13.30, 77.55, 13.30)), "demo seed"));
        return zones;
    }

    /** Closed GeoJSON ring from lng,lat pairs. */
    static List<double[]> ring(double... lngLat) {
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < lngLat.length; i += 2) {
            out.add(new double[]{lngLat[i], lngLat[i + 1]});
        }
        out.add(new double[]{lngLat[0], lngLat[1]});
        return out;
    }
}
