package com.moveinsync.zones.core.catalog;

import com.moveinsync.zones.core.zone.ZoneVersion;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Append-only store of zone versions, partitioned by tenant. Every method
 * takes the tenant id, so a lookup can never cross tenants by mistake.
 */
public interface ZoneRepository {

    /** Stores a new version. Its catalogVersion must be latestCatalogVersion(tenant) + 1. */
    void append(ZoneVersion version);

    long latestCatalogVersion(String tenantId);

    Optional<ZoneVersion> latest(String tenantId, String zoneId);

    List<ZoneVersion> history(String tenantId, String zoneId);

    /** For each zone, its newest version with catalogVersion <= the given one, skipping deleted zones. */
    List<ZoneVersion> activeAt(String tenantId, long catalogVersion);

    Set<String> tenants();
}
