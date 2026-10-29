package com.moveinsync.zones.core.validation;

/** One reason a polygon was rejected, e.g. code SELF_INTERSECTION. */
public record ValidationError(String code, String message) {

    @Override
    public String toString() {
        return code + ": " + message;
    }
}
