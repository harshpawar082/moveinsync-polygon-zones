package com.moveinsync.zones.core.index;

import com.moveinsync.zones.core.zone.ZoneVersion;

import java.util.List;

/** Builds an immutable snapshot (zones + index). Swappable, e.g. for failure-injection tests. */
public interface SnapshotBuilder {

    ZoneSnapshot build(String tenantId, long catalogVersion, List<ZoneVersion> zones);
}
