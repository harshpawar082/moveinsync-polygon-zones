package com.moveinsync.zones.core.validation;

import com.moveinsync.zones.core.geo.Ring;

import java.util.List;

/** Rejects rings whose vertices are all on one line (zero area). */
public final class NonZeroAreaRule implements PolygonRule {

    /** 1e-12 square degrees is roughly 0.01 square metres. */
    private static final double MIN_AREA_DEG2 = 1e-12;

    @Override
    public Phase phase() {
        return Phase.CONTAINMENT;
    }

    @Override
    public void check(PolygonDraft draft, List<ValidationError> errors) {
        List<Ring> rings = draft.rings();
        for (int r = 0; r < rings.size(); r++) {
            if (Math.abs(rings.get(r).signedArea()) < MIN_AREA_DEG2) {
                errors.add(new ValidationError("ZERO_AREA", "ring " + r + " has no area"));
            }
        }
    }
}
