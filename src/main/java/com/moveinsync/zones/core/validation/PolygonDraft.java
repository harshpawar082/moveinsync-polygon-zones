package com.moveinsync.zones.core.validation;

import com.moveinsync.zones.core.geo.Ring;

import java.util.List;

/**
 * A polygon being validated: the raw GeoJSON coordinates as received, and,
 * once the structural checks pass, the cleaned-up rings.
 */
public final class PolygonDraft {

    private final List<List<double[]>> raw;
    private List<Ring> rings;

    public PolygonDraft(List<List<double[]>> raw) {
        this.raw = raw;
    }

    public List<List<double[]>> raw() {
        return raw;
    }

    /** Only available to TOPOLOGY and CONTAINMENT rules. */
    public List<Ring> rings() {
        if (rings == null) {
            throw new IllegalStateException("rings are built only after structural checks pass");
        }
        return rings;
    }

    void setRings(List<Ring> rings) {
        this.rings = rings;
    }
}
