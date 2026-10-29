package com.moveinsync.zones.observability;

import com.moveinsync.zones.core.metrics.ZoneMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer implementation of the core's metrics interface. Exposed at
 * /actuator/prometheus. See README "Monitoring" for what to alert on.
 */
@Component
public class MicrometerZoneMetrics implements ZoneMetrics {

    private final MeterRegistry registry;
    private final Timer queryLatency;
    private final Map<String, AtomicLong> liveVersions = new ConcurrentHashMap<>();

    public MicrometerZoneMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.queryLatency = Timer.builder("zones.query.latency")
                .description("Time to answer one point-in-zone query (index lookup + exact test)")
                .publishPercentiles(0.5, 0.99)
                .register(registry);
    }

    @Override
    public void indexSwapped(String tenantId, long catalogVersion, long buildMillis, long stalenessMillis, int zoneCount) {
        registry.counter("zones.index.swaps", "tenant", tenantId).increment();
        Timer.builder("zones.index.build.time").tag("tenant", tenantId).register(registry)
                .record(buildMillis, TimeUnit.MILLISECONDS);
        Timer.builder("zones.index.staleness")
                .description("Time from an admin edit until the new index is live")
                .tag("tenant", tenantId).register(registry)
                .record(stalenessMillis, TimeUnit.MILLISECONDS);
        AtomicLong live = liveVersions.computeIfAbsent(tenantId,
                t -> registry.gauge("zones.index.catalog.version", Tags.of("tenant", t), new AtomicLong()));
        live.set(catalogVersion);
    }

    @Override
    public void indexRebuildFailed(String tenantId) {
        registry.counter("zones.index.rebuild.failures", "tenant", tenantId).increment();
    }

    @Override
    public void zoneChanged(String tenantId, String action) {
        registry.counter("zones.catalog.changes", "tenant", tenantId, "action", action).increment();
    }

    @Override
    public void zoneRejected(String code) {
        registry.counter("zones.catalog.rejections", "code", code).increment();
    }

    @Override
    public void pointQuery(long nanos, int matches) {
        queryLatency.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void pings(int accepted, int duplicates, int outOfOrder, int invalid) {
        registry.counter("zones.pings", "outcome", "accepted").increment(accepted);
        registry.counter("zones.pings", "outcome", "duplicate").increment(duplicates);
        registry.counter("zones.pings", "outcome", "out_of_order").increment(outOfOrder);
        registry.counter("zones.pings", "outcome", "invalid").increment(invalid);
    }

    @Override
    public void noGoAlert(String tenantId) {
        registry.counter("zones.nogo.alerts", "tenant", tenantId).increment();
    }
}
