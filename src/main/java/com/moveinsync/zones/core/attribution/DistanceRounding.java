package com.moveinsync.zones.core.attribution;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rounding policy for billed distance:
 * - all maths is done in double METRES and rounded only once, at the end;
 * - the total is rounded half-up to whole metres (0.001 km);
 * - the per-zone parts use the largest-remainder method so they add up to the
 *   rounded total exactly (no "30.4 + 11.6 = 42.001" disputes).
 */
public final class DistanceRounding {

    private DistanceRounding() {
    }

    /** @return km per key (3 decimals), in the iteration order of the input map. */
    public static Map<String, BigDecimal> partsInKm(Map<String, Double> metersByKey, double totalMeters) {
        long totalUnits = Math.round(totalMeters);
        List<String> keys = new ArrayList<>(metersByKey.keySet());
        long[] units = new long[keys.size()];
        double[] remainders = new double[keys.size()];
        long sum = 0;
        for (int i = 0; i < keys.size(); i++) {
            double m = metersByKey.get(keys.get(i));
            units[i] = (long) Math.floor(m);
            remainders[i] = m - units[i];
            sum += units[i];
        }
        long missing = totalUnits - sum;
        // hand out the missing metres to the largest remainders (ties: first key)
        while (missing > 0) {
            int best = -1;
            for (int i = 0; i < keys.size(); i++) {
                if (remainders[i] >= 0 && (best == -1 || remainders[i] > remainders[best])) {
                    best = i;
                }
            }
            if (best == -1) {
                break;
            }
            units[best]++;
            remainders[best] = -1; // each part gets at most one extra metre
            missing--;
        }
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            out.put(keys.get(i), BigDecimal.valueOf(units[i], 3));
        }
        return out;
    }

    public static BigDecimal totalInKm(double totalMeters) {
        return BigDecimal.valueOf(Math.round(totalMeters), 3);
    }
}
