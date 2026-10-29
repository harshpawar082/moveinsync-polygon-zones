package com.moveinsync.zones.core.attribution;

import com.moveinsync.zones.core.zone.ZoneVersion;

import java.util.List;

/**
 * Part of a segment, from fraction t0 to t1 (0 = segment start, 1 = end),
 * that lies inside exactly the listed zones.
 */
public record Piece(double t0, double t1, List<ZoneVersion> zones) {
}
