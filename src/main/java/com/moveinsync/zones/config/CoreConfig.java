package com.moveinsync.zones.config;

import com.moveinsync.zones.core.attribution.NightWindow;
import com.moveinsync.zones.core.catalog.InMemoryZoneRepository;
import com.moveinsync.zones.core.catalog.JournalZoneRepository;
import com.moveinsync.zones.core.catalog.SnapshotManager;
import com.moveinsync.zones.core.catalog.ZoneCatalogService;
import com.moveinsync.zones.core.catalog.ZoneQueryService;
import com.moveinsync.zones.core.catalog.ZoneRepository;
import com.moveinsync.zones.core.index.GridSnapshotBuilder;
import com.moveinsync.zones.core.index.SnapshotBuilder;
import com.moveinsync.zones.core.metrics.ZoneMetrics;
import com.moveinsync.zones.core.trip.TripService;
import com.moveinsync.zones.core.validation.PolygonValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Wires the plain-Java core into Spring. The core classes have no Spring
 * annotations at all, so they are unit-tested without starting a server.
 */
@Configuration
public class CoreConfig {

    private static final Logger log = LoggerFactory.getLogger(CoreConfig.class);

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public ZoneRepository zoneRepository(AppProperties props) {
        String path = props.storage().journalPath();
        if (path == null || path.isBlank()) {
            log.info("zone storage: in-memory only");
            return new InMemoryZoneRepository();
        }
        JournalZoneRepository repo = new JournalZoneRepository(Path.of(path));
        log.info("zone storage: journal recovered entries={} truncatedBytes={}",
                repo.recoveredEntries(), repo.truncatedBytes());
        return repo;
    }

    @Bean
    public SnapshotBuilder snapshotBuilder(AppProperties props) {
        return new GridSnapshotBuilder(props.index().cellSizeDeg(), props.index().maxCellsPerZone());
    }

    @Bean(destroyMethod = "close")
    public SnapshotManager snapshotManager(ZoneRepository repo, SnapshotBuilder builder, ZoneMetrics metrics,
                                           AppProperties props) {
        AppProperties.Index ix = props.index();
        SnapshotManager manager = new SnapshotManager(repo, builder, metrics, new SnapshotManager.Settings(
                ix.historyCacheSize(), ix.retryBaseMillis(), ix.retryMaxMillis(), ix.rebuildThreads()));
        manager.initialize();
        return manager;
    }

    @Bean
    public PolygonValidator polygonValidator(AppProperties props) {
        return PolygonValidator.standard(props.index().maxVerticesPerZone());
    }

    @Bean
    public NightWindow nightWindow(AppProperties props) {
        AppProperties.Trips t = props.trips();
        return new NightWindow(LocalTime.parse(t.nightStart()), LocalTime.parse(t.nightEnd()), ZoneId.of(t.timeZone()));
    }

    @Bean
    public ZoneCatalogService zoneCatalogService(ZoneRepository repo, SnapshotManager snapshots,
                                                 PolygonValidator validator, ZoneMetrics metrics, Clock clock) {
        return new ZoneCatalogService(repo, snapshots, validator, metrics, clock);
    }

    @Bean
    public ZoneQueryService zoneQueryService(SnapshotManager snapshots, NightWindow night, ZoneMetrics metrics,
                                             AppProperties props) {
        return new ZoneQueryService(snapshots, night, metrics, props.index().maxBatch());
    }

    @Bean
    public TripService tripService(SnapshotManager snapshots, NightWindow night, ZoneMetrics metrics, Clock clock,
                                   AppProperties props) {
        return new TripService(snapshots, night, metrics, clock, props.trips().maxPingsPerBatch());
    }
}
