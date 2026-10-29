package com.moveinsync.zones.core.error;

import java.util.List;

public class ConflictException extends ZoneServiceException {

    public ConflictException(String code, String message) {
        super(code, message, List.of());
    }

    @Override
    public Kind kind() {
        return Kind.CONFLICT;
    }
}
