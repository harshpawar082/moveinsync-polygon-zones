package com.moveinsync.zones.core.metrics;

/**
 * What the core reports for monitoring. The core does not depend on any
 * metrics library; the Spring layer implements this with Micrometer.
 * Every method has a no-op default, so tests override only what they check.
 */
public interface ZoneMetrics {

    ZoneMetrics NOOP = new ZoneMetrics() {
    };

    /** A new index went live. stalenessMillis = time from the first unpublished edit to this swap. */
    default void indexSwapped(String tenantId, long catalogVersion, long buildMillis, long stalenessMillis, int zoneCount) {
    }

    default void indexRebuildFailed(String tenantId) {
    }

    default void zoneChanged(String tenantId, String action) {
    }

    default void zoneRejected(String code) {
    }

    default void pointQuery(long nanos, int matches) {
    }

    default void pings(int accepted, int duplicates, int outOfOrder, int invalid) {
    }

    default void noGoAlert(String tenantId) {
    }
}
