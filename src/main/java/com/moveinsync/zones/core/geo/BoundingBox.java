package com.moveinsync.zones.core.geo;

/** Axis-aligned box in lng (x) / lat (y) degrees. */
public record BoundingBox(double minX, double minY, double maxX, double maxY) {

    public static BoundingBox ofSegment(double ax, double ay, double bx, double by) {
        return new BoundingBox(Math.min(ax, bx), Math.min(ay, by), Math.max(ax, bx), Math.max(ay, by));
    }

    public boolean contains(double x, double y) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY;
    }

    public boolean intersects(BoundingBox other) {
        return other.minX <= maxX && other.maxX >= minX && other.minY <= maxY && other.maxY >= minY;
    }

    public BoundingBox expand(double by) {
        return new BoundingBox(minX - by, minY - by, maxX + by, maxY + by);
    }
}
