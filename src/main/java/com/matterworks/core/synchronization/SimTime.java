package com.matterworks.core.synchronization;

/**
 * Global tick timing context.
 *
 * We keep it static/volatile so it's visible to tick worker threads too.
 * This enables dt-based simulation without changing tick(long) signatures everywhere.
 */
public final class SimTime {

    private static volatile long currentTick = 0L;

    // Real elapsed time between tick starts (seconds).
    private static volatile double deltaSeconds = 0.05; // default 20 TPS

    // Reference tick duration (seconds) for converting legacy "ticks" to seconds.
    private static final double BASE_TICK_SECONDS = 0.05; // 50ms => 20 TPS

    private SimTime() {}

    public static void update(long tick, double dtSeconds) {
        currentTick = tick;
        deltaSeconds = dtSeconds;
    }

    public static long getCurrentTick() {
        return currentTick;
    }

    public static double deltaSeconds() {
        return deltaSeconds;
    }

    public static double baseTickSeconds() {
        return BASE_TICK_SECONDS;
    }
}
