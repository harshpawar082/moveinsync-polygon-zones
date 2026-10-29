package com.moveinsync.zones.core.zone;

public enum ZoneType {
    /** Informational, e.g. city limits. Not used for billing. */
    CITY_LIMIT(false),
    /** A tariff zone billed at its own rate. */
    TARIFF(true),
    /** An airport zone with a surcharge; billed like a tariff zone. */
    AIRPORT(true),
    /** A cab must never enter it at night. Never billed. */
    NO_GO(false);

    private final boolean billable;

    ZoneType(boolean billable) {
        this.billable = billable;
    }

    public boolean billable() {
        return billable;
    }

    /** No-go zones follow the LATEST version on live trips; everything else is pinned. */
    public boolean safetyZone() {
        return this == NO_GO;
    }
}
