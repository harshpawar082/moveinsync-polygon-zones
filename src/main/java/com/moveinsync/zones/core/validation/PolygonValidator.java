package com.moveinsync.zones.core.validation;

import com.moveinsync.zones.core.geo.Polygon;
import com.moveinsync.zones.core.geo.Ring;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs all {@link PolygonRule}s phase by phase and returns a ready-to-use
 * {@link Polygon}, or throws {@link InvalidPolygonException} listing every
 * problem found in the first failing phase.
 */
public final class PolygonValidator {

    private final List<PolygonRule> rules;

    public PolygonValidator(List<PolygonRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public static PolygonValidator standard(int maxTotalVertices) {
        List<PolygonRule> rules = new ArrayList<>();
        rules.add(new CoordinateRangeRule(maxTotalVertices));
        rules.add(new ClosedRingRule());
        rules.add(new MinimumVerticesRule());
        rules.add(new NonZeroAreaRule());
        rules.add(new SelfIntersectionRule());
        rules.add(new HoleContainmentRule());
        return new PolygonValidator(rules);
    }

    public Polygon validate(List<List<double[]>> coordinates) {
        PolygonDraft draft = new PolygonDraft(coordinates);
        for (PolygonRule.Phase phase : PolygonRule.Phase.values()) {
            List<ValidationError> errors = new ArrayList<>();
            for (PolygonRule rule : rules) {
                if (rule.phase() == phase) {
                    rule.check(draft, errors);
                }
            }
            if (!errors.isEmpty()) {
                throw new InvalidPolygonException(errors);
            }
            if (phase == PolygonRule.Phase.STRUCTURE) {
                List<Ring> rings = new ArrayList<>();
                for (List<double[]> ring : coordinates) {
                    rings.add(Ring.fromPositions(ring));
                }
                draft.setRings(rings);
            }
        }
        return new Polygon(draft.rings().get(0), draft.rings().subList(1, draft.rings().size()));
    }
}
