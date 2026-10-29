package com.moveinsync.zones.core.index;

import com.moveinsync.zones.core.geo.BoundingBox;
import com.moveinsync.zones.core.zone.ZoneVersion;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class GridSnapshotBuilder implements SnapshotBuilder {

    private final double cellSizeDeg;
    private final int maxCellsPerZone;

    public GridSnapshotBuilder(double cellSizeDeg, int maxCellsPerZone) {
        this.cellSizeDeg = cellSizeDeg;
        this.maxCellsPerZone = maxCellsPerZone;
    }

    @Override
    public ZoneSnapshot build(String tenantId, long catalogVersion, List<ZoneVersion> zones) {
        long start = System.nanoTime();
        List<ZoneVersion> sorted = new ArrayList<>(zones);
        sorted.sort(Comparator.comparing(ZoneVersion::zoneId)); // same input -> same index layout
        List<BoundingBox> boxes = new ArrayList<>(sorted.size());
        for (ZoneVersion z : sorted) {
            boxes.add(z.polygon().bbox());
        }
        GridIndex index = new GridIndex(boxes, cellSizeDeg, maxCellsPerZone);
        long millis = (System.nanoTime() - start) / 1_000_000;
        return new ZoneSnapshot(tenantId, catalogVersion, sorted, index, Instant.now(), millis);
    }
}
