package com.moveinsync.zones.core.validation;

import com.moveinsync.zones.core.geo.Ring;

import java.util.List;

/** A ring needs at least 3 distinct vertices (4 positions with the closing one). */
public final class MinimumVerticesRule implements PolygonRule {

    @Override
    public Phase phase() {
        return Phase.STRUCTURE;
    }

    @Override
    public void check(PolygonDraft draft, List<ValidationError> errors) {
        List<List<double[]>> raw = draft.raw();
        if (raw == null) {
            return;
        }
        for (int r = 0; r < raw.size(); r++) {
            List<double[]> ring = raw.get(r);
            if (ring == null) {
                continue;
            }
            boolean malformed = false;
            for (double[] p : ring) {
                if (p == null || p.length < 2) {
                    malformed = true;
                    break;
                }
            }
            if (malformed) {
                continue; // reported by CoordinateRangeRule
            }
            int distinct = Ring.fromPositions(ring).size();
            if (distinct < 3) {
                errors.add(new ValidationError("TOO_FEW_VERTICES",
                        "ring " + r + " has " + distinct + " distinct vertices, needs at least 3"));
            }
        }
    }
}
