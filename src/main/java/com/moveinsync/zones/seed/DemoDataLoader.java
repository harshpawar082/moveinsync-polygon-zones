package com.moveinsync.zones.seed;

import com.moveinsync.zones.config.AppProperties;
import com.moveinsync.zones.core.catalog.ZoneCatalogService;
import com.moveinsync.zones.core.catalog.ZoneRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Loads the demo zones once, on the first start (skipped if the tenant already has zones). */
@Component
public class DemoDataLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataLoader.class);

    private final AppProperties props;
    private final ZoneRepository repository;
    private final ZoneCatalogService catalog;

    public DemoDataLoader(AppProperties props, ZoneRepository repository, ZoneCatalogService catalog) {
        this.props = props;
        this.repository = repository;
        this.catalog = catalog;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.seed().enabled()) {
            return;
        }
        if (repository.latestCatalogVersion("acme") == 0) {
            for (ZoneCatalogService.ZoneDraft d : DemoZones.acme()) {
                catalog.create("acme", "seed", d);
            }
            log.info("seeded demo zones tenant=acme count={}", DemoZones.acme().size());
        }
        if (repository.latestCatalogVersion("globex") == 0) {
            for (ZoneCatalogService.ZoneDraft d : DemoZones.globex()) {
                catalog.create("globex", "seed", d);
            }
            log.info("seeded demo zones tenant=globex count={}", DemoZones.globex().size());
        }
    }
}
