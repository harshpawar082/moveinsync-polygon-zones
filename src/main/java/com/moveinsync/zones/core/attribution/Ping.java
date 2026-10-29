package com.moveinsync.zones.core.attribution;

import com.moveinsync.zones.core.geo.GeoPoint;

/** One GPS fix of a cab. */
public record Ping(double lat, double lng, long epochMillis) {

    public GeoPoint point() {
        return new GeoPoint(lat, lng);
    }
}
