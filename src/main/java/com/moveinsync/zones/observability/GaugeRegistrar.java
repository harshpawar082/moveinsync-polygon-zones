package com.moveinsync.zones.observability;

import com.moveinsync.zones.core.catalog.SnapshotManager;
import com.moveinsync.zones.core.trip.TripService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Gauges read live state on every scrape. */
@Component
public class GaugeRegistrar {

    public GaugeRegistrar(MeterRegistry registry, SnapshotManager snapshots, TripService trips) {
        Gauge.builder("zones.index.pending.versions", snapshots, s -> s.totalPendingVersions())
                .description("Committed zone edits not yet live in the index (should return to 0 within seconds)")
                .register(registry);
        Gauge.builder("zones.index.failing.tenants", snapshots, s -> s.failingTenants())
                .description("Tenants whose last index rebuild failed")
                .register(registry);
        Gauge.builder("zones.trips.active", trips, t -> t.activeTrips())
                .description("Trips started and not yet ended")
                .register(registry);
    }
}
