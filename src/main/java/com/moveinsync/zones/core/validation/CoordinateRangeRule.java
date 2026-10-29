package com.moveinsync.zones.core.validation;

import java.util.List;

/** Every position must be [lng, lat] with finite, in-range values; caps total size. */
public final class CoordinateRangeRule implements PolygonRule {

    private final int maxTotalVertices;

    public CoordinateRangeRule(int maxTotalVertices) {
        this.maxTotalVertices = maxTotalVertices;
    }

    @Override
    public Phase phase() {
        return Phase.STRUCTURE;
    }

    @Override
    public void check(PolygonDraft draft, List<ValidationError> errors) {
        List<List<double[]>> raw = draft.raw();
        if (raw == null || raw.isEmpty()) {
            errors.add(new ValidationError("NO_RINGS", "polygon needs an outer ring"));
            return;
        }
        int total = 0;
        for (int r = 0; r < raw.size(); r++) {
            List<double[]> ring = raw.get(r);
            if (ring == null) {
                errors.add(new ValidationError("MALFORMED_RING", "ring " + r + " is null"));
                continue;
            }
            total += ring.size();
            for (int i = 0; i < ring.size(); i++) {
                double[] p = ring.get(i);
                if (p == null || p.length < 2) {
                    errors.add(new ValidationError("MALFORMED_POSITION",
                            "ring " + r + " position " + i + " must be [lng, lat]"));
                    return;
                }
                double lng = p[0];
                double lat = p[1];
                boolean ok = Double.isFinite(lng) && Double.isFinite(lat)
                        && lng >= -180 && lng <= 180 && lat >= -90 && lat <= 90;
                if (!ok) {
                    errors.add(new ValidationError("COORDINATE_OUT_OF_RANGE",
                            "ring " + r + " position " + i + " is not a valid [lng, lat]"));
                    return;
                }
            }
        }
        if (total > maxTotalVertices) {
            errors.add(new ValidationError("TOO_MANY_VERTICES",
                    "polygon has " + total + " positions, limit is " + maxTotalVertices));
        }
    }
}
