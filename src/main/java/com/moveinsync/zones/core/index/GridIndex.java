package com.moveinsync.zones.core.index;

import com.moveinsync.zones.core.geo.BoundingBox;
import com.moveinsync.zones.core.geo.GeometryMath;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Uniform grid over lat/lng. Each zone is registered in every cell its
 * bounding box touches; a point query looks at ONE cell: O(1) to find the
 * cell, then an exact test on the few zones registered there.
 *
 * Why a grid and not an R-tree: GPS pings are spread over a city, zones are of
 * similar size (wards, tariff areas, airports), the cell lookup is one hash
 * lookup with no tree walk, the build is a simple loop (fast to rebuild in the
 * background), and memory is easy to predict. An R-tree adapts better when zone
 * sizes vary by orders of magnitude; we handle that case with an "oversized"
 * list instead (zones covering more than maxCellsPerZone cells are checked by
 * bounding box on every query).
 */
public final class GridIndex implements SpatialIndex {

    private final double cellSize;
    private final Map<Long, int[]> cells;
    private final int[] oversized;
    private final BoundingBox[] boxes;
    private final long registrations;

    public GridIndex(List<BoundingBox> zoneBoxes, double cellSizeDeg, int maxCellsPerZone) {
        if (cellSizeDeg <= 0) {
            throw new IllegalArgumentException("cellSizeDeg must be > 0");
        }
        this.cellSize = cellSizeDeg;
        this.boxes = zoneBoxes.toArray(new BoundingBox[0]);

        double tol = GeometryMath.BOUNDARY_TOLERANCE_DEG;
        Map<Long, IntBuffer> building = new HashMap<>();
        List<Integer> big = new ArrayList<>();
        long regs = 0;

        for (int z = 0; z < boxes.length; z++) {
            BoundingBox box = boxes[z].expand(tol);
            long c0 = col(box.minX());
            long c1 = col(box.maxX());
            long r0 = col(box.minY());
            long r1 = col(box.maxY());
            long cellCount = (c1 - c0 + 1) * (r1 - r0 + 1);
            if (cellCount > maxCellsPerZone) {
                big.add(z);
                continue;
            }
            for (long c = c0; c <= c1; c++) {
                for (long r = r0; r <= r1; r++) {
                    IntBuffer buf = building.get(key(c, r));
                    if (buf == null) {
                        buf = new IntBuffer();
                        building.put(key(c, r), buf);
                    }
                    buf.add(z);
                    regs++;
                }
            }
        }

        Map<Long, int[]> frozen = new HashMap<>(building.size() * 2);
        for (Map.Entry<Long, IntBuffer> e : building.entrySet()) {
            frozen.put(e.getKey(), e.getValue().toArray());
        }
        this.cells = frozen;
        this.oversized = new int[big.size()];
        for (int i = 0; i < big.size(); i++) {
            oversized[i] = big.get(i);
        }
        this.registrations = regs;
    }

    @Override
    public int[] candidatesAt(double x, double y) {
        int[] inCell = cells.get(key(col(x), col(y)));
        if (oversized.length == 0) {
            if (inCell == null) {
                return EMPTY;
            }
            return inCell;
        }
        int[] fromCell = inCell == null ? EMPTY : inCell;
        int[] out = Arrays.copyOf(fromCell, fromCell.length + oversized.length);
        System.arraycopy(oversized, 0, out, fromCell.length, oversized.length);
        return out;
    }

    @Override
    public int[] candidatesIn(BoundingBox box) {
        long c0 = col(box.minX());
        long c1 = col(box.maxX());
        long r0 = col(box.minY());
        long r1 = col(box.maxY());
        long cellCount = (c1 - c0 + 1) * (r1 - r0 + 1);

        boolean[] seen = new boolean[boxes.length];
        IntBuffer result = new IntBuffer();
        if (cellCount > 4096) {
            // A very long segment: scanning all boxes is cheaper than walking thousands of cells.
            for (int z = 0; z < boxes.length; z++) {
                if (boxes[z].intersects(box)) {
                    result.add(z);
                }
            }
            return result.toArray();
        }
        for (long c = c0; c <= c1; c++) {
            for (long r = r0; r <= r1; r++) {
                int[] ids = cells.get(key(c, r));
                if (ids == null) {
                    continue;
                }
                for (int z : ids) {
                    if (!seen[z] && boxes[z].intersects(box)) {
                        seen[z] = true;
                        result.add(z);
                    }
                }
            }
        }
        for (int z : oversized) {
            if (!seen[z] && boxes[z].intersects(box)) {
                seen[z] = true;
                result.add(z);
            }
        }
        return result.toArray();
    }

    @Override
    public long approxMemoryBytes() {
        // per cell: HashMap node (~32 B) + Long key (16 B) + int[] header (16 B); 4 B per registration
        return cells.size() * 64L + registrations * 4L + oversized.length * 4L;
    }

    @Override
    public String describe() {
        return "grid(cell=" + cellSize + " deg, cells=" + cells.size() + ", registrations=" + registrations
                + ", oversized=" + oversized.length + ")";
    }

    public int cellCount() {
        return cells.size();
    }

    public long registrations() {
        return registrations;
    }

    private long col(double v) {
        return (long) Math.floor(v / cellSize);
    }

    private static long key(long col, long row) {
        return (col << 32) ^ (row & 0xffffffffL);
    }

    private static final int[] EMPTY = new int[0];

    /** Minimal growable int array used while building. */
    private static final class IntBuffer {
        private int[] data = new int[4];
        private int size;

        void add(int v) {
            if (size == data.length) {
                data = Arrays.copyOf(data, size * 2);
            }
            data[size] = v;
            size++;
        }

        int[] toArray() {
            return Arrays.copyOf(data, size);
        }
    }
}
