package com.moveinsync.zones.core.geo;

/**
 * A point on the earth in degrees. Internally the geometry code works with
 * x = longitude and y = latitude, the same order GeoJSON uses.
 */
public record GeoPoint(double lat, double lng) {

    public double x() {
        return lng;
    }

    public double y() {
        return lat;
    }

    public static boolean isValid(double lat, double lng) {
        return Double.isFinite(lat) && Double.isFinite(lng)
                && lat >= -90.0 && lat <= 90.0
                && lng >= -180.0 && lng <= 180.0;
    }

    /** Point at fraction t (0..1) of the straight line a -> b in lat/lng space. */
    public static GeoPoint lerp(GeoPoint a, GeoPoint b, double t) {
        double lat = a.lat + t * (b.lat - a.lat);
        double lng = a.lng + t * (b.lng - a.lng);
        return new GeoPoint(lat, lng);
    }
}
