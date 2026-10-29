package com.moveinsync.zones.core.error;

import java.util.List;

/** The durable store could not be written. The edit was NOT applied. */
public class StorageUnavailableException extends ZoneServiceException {

    public StorageUnavailableException(String message, Throwable cause) {
        super("STORAGE_UNAVAILABLE", message, List.of());
        initCause(cause);
    }

    @Override
    public Kind kind() {
        return Kind.UNAVAILABLE;
    }
}
