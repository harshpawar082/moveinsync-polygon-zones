package com.moveinsync.zones.core.error;

import java.util.List;

public class BadRequestException extends ZoneServiceException {

    public BadRequestException(String code, String message) {
        super(code, message, List.of());
    }

    public BadRequestException(String code, String message, List<String> details) {
        super(code, message, details);
    }

    @Override
    public Kind kind() {
        return Kind.BAD_REQUEST;
    }
}
