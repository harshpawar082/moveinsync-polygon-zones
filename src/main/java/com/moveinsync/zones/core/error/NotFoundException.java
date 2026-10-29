package com.moveinsync.zones.core.error;

import java.util.List;

/**
 * Also used for resources that exist but belong to ANOTHER tenant, so a
 * caller can never learn that another client's zone or trip exists.
 */
public class NotFoundException extends ZoneServiceException {

    public NotFoundException(String code, String message) {
        super(code, message, List.of());
    }

    @Override
    public Kind kind() {
        return Kind.NOT_FOUND;
    }
}
