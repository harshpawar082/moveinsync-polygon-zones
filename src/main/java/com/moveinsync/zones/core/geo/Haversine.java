package com.moveinsync.zones.core.geo;

/**
 * Great-circle distance on a spherical earth.
 *
 * Error: the earth is an ellipsoid, so a sphere is off by up to ~0.3% on long
 * north-south lines. For billing we only need consistency between runs and a
 * small, known error; GPS itself is only accurate to 3-10 m.
 */
public final class Haversine {

    /** Mean earth radius (IUGG), metres. */
    public static final double EARTH_RADIUS_M = 6_371_008.8;

    private Haversine() {
    }

    public static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dPhi = Math.toRadians(lat2 - lat1);
        double dLambda = Math.toRadians(lng2 - lng1);

        double sinDPhi = Math.sin(dPhi / 2);
        double sinDLambda = Math.sin(dLambda / 2);
        double h = sinDPhi * sinDPhi + Math.cos(phi1) * Math.cos(phi2) * sinDLambda * sinDLambda;
        if (h > 1.0) {
            h = 1.0;
        }
        return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(h));
    }

    public static double distanceMeters(GeoPoint a, GeoPoint b) {
        return distanceMeters(a.lat(), a.lng(), b.lat(), b.lng());
    }
}
