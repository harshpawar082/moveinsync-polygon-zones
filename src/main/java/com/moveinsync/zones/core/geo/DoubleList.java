package com.moveinsync.zones.core.geo;

import java.util.Arrays;

/** A growable array of primitive doubles (avoids boxing in hot loops). */
public final class DoubleList {

    private double[] data;
    private int size;

    public DoubleList(int initialCapacity) {
        data = new double[Math.max(4, initialCapacity)];
    }

    public void add(double value) {
        if (size == data.length) {
            data = Arrays.copyOf(data, size * 2);
        }
        data[size] = value;
        size++;
    }

    public int size() {
        return size;
    }

    public double get(int i) {
        return data[i];
    }

    public void sort() {
        Arrays.sort(data, 0, size);
    }

    public void clear() {
        size = 0;
    }
}
