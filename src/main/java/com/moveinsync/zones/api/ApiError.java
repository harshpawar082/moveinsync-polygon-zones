package com.moveinsync.zones.api;

import com.moveinsync.zones.observability.RequestIdFilter;
import org.slf4j.MDC;

import java.util.List;

/**
 * The one error shape of the API. "code" is stable and machine-readable;
 * "message" is for humans; "requestId" lets support find the matching log
 * line. Never contains stack traces, SQL, file paths, or personal data.
 */
public record ApiError(String code, String message, List<String> details, String requestId) {

    public static ApiError of(String code, String message, List<String> details) {
        return new ApiError(code, message, details, MDC.get(RequestIdFilter.MDC_KEY));
    }
}
