package com.moveinsync.zones.core.attribution;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * The hours in which no-go zones are enforced, in the client's local time,
 * e.g. 22:00 to 06:00 Asia/Kolkata. A window may wrap past midnight.
 */
public record NightWindow(LocalTime start, LocalTime end, ZoneId zone) {

    public boolean contains(long epochMillis) {
        LocalTime t = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalTime();
        if (start.isBefore(end)) {
            return !t.isBefore(start) && t.isBefore(end);
        }
        // wraps midnight: 22:00 -> 06:00
        return !t.isBefore(start) || t.isBefore(end);
    }
}
