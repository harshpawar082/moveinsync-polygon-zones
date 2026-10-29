package com.moveinsync.zones.core.validation;

import java.util.List;

/** GeoJSON rule: the first and last position of every ring must be identical. */
public final class ClosedRingRule implements PolygonRule {

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
            if (ring == null || ring.isEmpty()) {
                continue;
            }
            double[] first = ring.get(0);
            double[] last = ring.get(ring.size() - 1);
            if (first == null || last == null || first.length < 2 || last.length < 2) {
                continue;
            }
            if (ring.size() < 2 || first[0] != last[0] || first[1] != last[1]) {
                errors.add(new ValidationError("RING_NOT_CLOSED",
                        "ring " + r + " must end with the same position it starts with"));
            }
        }
    }
}
