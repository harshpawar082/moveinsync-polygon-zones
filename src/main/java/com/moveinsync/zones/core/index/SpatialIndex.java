package com.moveinsync.zones.core.index;

import com.moveinsync.zones.core.geo.BoundingBox;

/**
 * Narrows "which of the N zones could contain this point?" down to a few
 * candidates. Results are positions in the snapshot's zone list. Candidates are
 * only "maybe": the exact point-in-polygon test is done by the caller.
 *
 * Implementations must be immutable after construction (they are shared by
 * many reader threads without locks). Returned arrays must not be modified.
 */
public interface SpatialIndex {

    int[] candidatesAt(double x, double y);

    int[] candidatesIn(BoundingBox box);

    /** Rough heap size of the index structure itself (not the polygons). */
    long approxMemoryBytes();

    String describe();
}
