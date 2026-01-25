package com.matterworks.core.synchronization;

/**
 * Shared simulation timing for dt-based machines.
 *
 * FactoryLoop updates this once per loop iteration.
 * Machines read dtSeconds to advance internal accumulators.
 */
public final class SimulationTime {

    private static volatile double dtSeconds = 0.05; // fallback (20 TPS)
    private static volatile long nowNano = System.nanoTime();

    private SimulationTime() {}

    public static double getDtSeconds() {
        return dtSeconds;
    }

    public static long getNowNano() {
        return nowNano;
    }

    static void update(long nowNanoValue, double dtSecondsValue) {
        nowNano = nowNanoValue;

        double v = dtSecondsValue;
        if (Double.isNaN(v) || Double.isInfinite(v) || v <= 0.0) v = 0.05;

        // clamp to avoid insane bursts after pauses
        if (v > 0.25) v = 0.25;

        dtSeconds = v;
    }
}
