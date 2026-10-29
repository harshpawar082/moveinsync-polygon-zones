package com.moveinsync.zones.core.geo;

/** Where a point is relative to a ring or polygon. */
public enum Location {
    INSIDE,
    OUTSIDE,
    /** Within {@link GeometryMath#BOUNDARY_TOLERANCE_DEG} of an edge or vertex. */
    BOUNDARY
}
