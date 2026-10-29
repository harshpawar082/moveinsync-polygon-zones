package com.moveinsync.zones.core.validation;

import java.util.List;

/**
 * One validation rule. New rules can be added without touching the
 * validator (open/closed principle); each rule is unit-tested on its own.
 */
public interface PolygonRule {

    /** Rules run phase by phase; a later phase runs only if earlier phases found nothing. */
    enum Phase {
        STRUCTURE,
        TOPOLOGY,
        CONTAINMENT
    }

    Phase phase();

    void check(PolygonDraft draft, List<ValidationError> errors);
}
