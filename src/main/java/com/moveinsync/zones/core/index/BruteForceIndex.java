package com.moveinsync.zones.core.index;

import com.moveinsync.zones.core.geo.BoundingBox;

import java.util.List;

/**
 * Returns every zone whose bounding box matches. O(N) per query.
 * Used as the trusted "oracle" in tests and as the baseline in the benchmark.
 */
public final class BruteForceIndex implements SpatialIndex {

    private final BoundingBox[] boxes;

    public BruteForceIndex(List<BoundingBox> boxes) {
        this.boxes = boxes.toArray(new BoundingBox[0]);
    }

    @Override
    public int[] candidatesAt(double x, double y) {
        int count = 0;
        int[] tmp = new int[boxes.length];
        for (int i = 0; i < boxes.length; i++) {
            if (boxes[i].contains(x, y)) {
                tmp[count] = i;
                count++;
            }
        }
        return java.util.Arrays.copyOf(tmp, count);
    }

    @Override
    public int[] candidatesIn(BoundingBox box) {
        int count = 0;
        int[] tmp = new int[boxes.length];
        for (int i = 0; i < boxes.length; i++) {
            if (boxes[i].intersects(box)) {
                tmp[count] = i;
                count++;
            }
        }
        return java.util.Arrays.copyOf(tmp, count);
    }

    @Override
    public long approxMemoryBytes() {
        return 16L + boxes.length * 8L;
    }

    @Override
    public String describe() {
        return "brute-force(" + boxes.length + " zones)";
    }
}
