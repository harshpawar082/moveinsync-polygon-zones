package com.moveinsync.zones.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** Everything configurable, bound from the "app" section of application.yml. */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Index index, Trips trips, Storage storage, Security security, Seed seed) {

    public record Index(double cellSizeDeg, int maxCellsPerZone, int historyCacheSize, long retryBaseMillis,
                        long retryMaxMillis, int rebuildThreads, int maxVerticesPerZone, int maxBatch) {
    }

    public record Trips(String nightStart, String nightEnd, String timeZone, int maxPingsPerBatch) {
    }

    /** Blank journalPath = in-memory only (used by tests). */
    public record Storage(String journalPath) {
    }

    public record Security(String jwtSecret, long tokenTtlMinutes, List<User> users) {

        /** Demo users. A real deployment would use the company identity provider instead. */
        public record User(String username, String password, String tenant, String role) {
        }
    }

    public record Seed(boolean enabled) {
    }
}
