package com.moveinsync.zones.core.validation;

import com.moveinsync.zones.core.geo.Location;
import com.moveinsync.zones.core.geo.Ring;

import java.util.List;

/**
 * Every hole must lie inside the outer ring and not inside another hole.
 * Runs after {@link SelfIntersectionRule}, which already proved no rings
 * cross or touch, so testing one vertex per hole is enough.
 */
public final class HoleContainmentRule implements PolygonRule {

    @Override
    public Phase phase() {
        return Phase.CONTAINMENT;
    }

    @Override
    public void check(PolygonDraft draft, List<ValidationError> errors) {
        List<Ring> rings = draft.rings();
        Ring outer = rings.get(0);
        for (int h = 1; h < rings.size(); h++) {
            Ring hole = rings.get(h);
            double x = hole.x(0);
            double y = hole.y(0);
            if (outer.locate(x, y) != Location.INSIDE) {
                errors.add(new ValidationError("HOLE_OUTSIDE_OUTER", "hole ring " + h + " is not inside the outer ring"));
                continue;
            }
            for (int g = 1; g < rings.size(); g++) {
                if (g != h && rings.get(g).locate(x, y) == Location.INSIDE) {
                    errors.add(new ValidationError("HOLE_INSIDE_HOLE", "hole ring " + h + " is inside hole ring " + g));
                    break;
                }
            }
        }
    }
}
