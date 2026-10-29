package com.moveinsync.zones.core.error;

import java.util.List;

/**
 * Base class for every expected, client-facing error of the core.
 * The API layer maps {@link #kind()} to an HTTP status in ONE place.
 * Messages must never contain personal data (only ids).
 */
public abstract class ZoneServiceException extends RuntimeException {

    public enum Kind {
        BAD_REQUEST,
        NOT_FOUND,
        CONFLICT,
        UNAVAILABLE
    }

    private final String code;
    private final List<String> details;

    protected ZoneServiceException(String code, String message, List<String> details) {
        super(message);
        this.code = code;
        this.details = List.copyOf(details);
    }

    public abstract Kind kind();

    public String code() {
        return code;
    }

    public List<String> details() {
        return details;
    }
}
