package com.matterworks.core.managers;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * Extremely lightweight tick profiler (allocation-light).
 *
 * Key goals:
 * - Avoid HashMap/ArrayList/sort allocations in hot path.
 * - Provide GC delta info on heavy samples to diagnose "random" spikes.
 *
 * Behavior:
 * - Sample normally at a low frequency.
 * - When an over-budget tick is detected, we arm a profiling window for the next N ticks.
 *
 * You can disable it via:
 * -Dmw.profiler=false
 */
public final class WorldTickProfiler {

    private WorldTickProfiler() {}

    private static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("mw.profiler", "true"));

    /** Profile window end tick (inclusive). Disabled when < 0. */
    private static volatile long profileWindowUntilTick = -1;

    /** Throttle profile printing to avoid log spam. */
    private static volatile long lastProfilePrintMs = 0;

    /**
     * Normal sampling period (ticks).
     * Old was 20 (1 sec @ 20 TPS). Lower overhead if we increase this.
     */
    private static final long NORMAL_SAMPLE_PERIOD_TICKS = 100L; // every ~5s @ 20 TPS

    /** Maximum distinct machine classes tracked in one sample. */
    private static final int MAX_CLASSES = 64;

    /** Top N classes printed. */
    private static final int TOP_N = 10;

    /** Reusable sample instance (single-threaded tick loop). */
    private static final Sample REUSABLE = new Sample();

    /** GC baseline for delta reporting (only updated when we print). */
    private static volatile long lastPrintedGcCount = -1;
    private static volatile long lastPrintedGcTimeMs = -1;

    public static boolean shouldSample(long tick) {
        if (!ENABLED) return false;
        return (tick % NORMAL_SAMPLE_PERIOD_TICKS == 0L) ||
                (profileWindowUntilTick >= 0 && tick <= profileWindowUntilTick);
    }

    /** Arms a profiling window for the next {@code durationTicks} ticks. */
    public static void triggerWindow(long currentTick, long durationTicks) {
        if (!ENABLED) return;
        long until = currentTick + Math.max(1, durationTicks);
        long prev = profileWindowUntilTick;
        if (prev < until) profileWindowUntilTick = until;
    }

    public static Sample beginSample(long tick) {
        REUSABLE.reset(tick);
        return REUSABLE;
    }

    public static final class Sample {

        private long tick;

        // Parallel arrays (no HashMap)
        private final Class<?>[] classes = new Class<?>[MAX_CLASSES];
        private final long[] totalNs = new long[MAX_CLASSES];
        private final long[] maxNs = new long[MAX_CLASSES];
        private final int[] count = new int[MAX_CLASSES];
        private int size = 0;

        private Sample() {}

        private void reset(long tick) {
            this.tick = tick;
            // we don't need to clear arrays fully; just reset size and overwrite slots
            this.size = 0;
        }

        public void record(Class<?> cls, long dtNs) {
            if (cls == null) return;

            // linear search; size is small (< ~20 distinct classes)
            for (int i = 0; i < size; i++) {
                if (classes[i] == cls) {
                    count[i]++;
                    totalNs[i] += dtNs;
                    if (dtNs > maxNs[i]) maxNs[i] = dtNs;
                    return;
                }
            }

            // new class slot
            if (size < MAX_CLASSES) {
                classes[size] = cls;
                count[size] = 1;
                totalNs[size] = dtNs;
                maxNs[size] = dtNs;
                size++;
            } else {
                // overflow: ignore (should not happen in this project)
            }
        }

        public void end(long worldTickNs, int machineCount) {
            if (size <= 0) return;

            long totalMs = worldTickNs / 1_000_000L;

            boolean inWindow = (profileWindowUntilTick >= 0 && tick <= profileWindowUntilTick);

            // Normal mode: print only if heavy.
            // Debug window: print also when moderately heavy, but throttle.
            long thresholdMs = inWindow ? 10L : 40L;
            if (totalMs < thresholdMs) return;

            long now = System.currentTimeMillis();
            if (inWindow && (now - lastProfilePrintMs) < 500L) return; // throttle
            lastProfilePrintMs = now;

            // GC delta since last printed profile (helps diagnose pauses)
            long gcCount = getTotalGcCount();
            long gcTimeMs = getTotalGcTimeMs();

            long dGcCount = (lastPrintedGcCount >= 0) ? (gcCount - lastPrintedGcCount) : 0;
            long dGcTimeMs = (lastPrintedGcTimeMs >= 0) ? (gcTimeMs - lastPrintedGcTimeMs) : 0;

            lastPrintedGcCount = gcCount;
            lastPrintedGcTimeMs = gcTimeMs;

            // Select top N by totalNs without sorting/allocations
            int limit = Math.min(TOP_N, size);
            int[] topIdx = new int[limit];
            for (int i = 0; i < limit; i++) topIdx[i] = -1;

            for (int i = 0; i < size; i++) {
                long v = totalNs[i];

                // find insertion position in topIdx (descending)
                for (int k = 0; k < limit; k++) {
                    int cur = topIdx[k];
                    long curV = (cur >= 0) ? totalNs[cur] : Long.MIN_VALUE;

                    if (cur < 0 || v > curV) {
                        // shift right
                        for (int s = limit - 1; s > k; s--) topIdx[s] = topIdx[s - 1];
                        topIdx[k] = i;
                        break;
                    }
                }
            }

            StringBuilder sb = new StringBuilder(512);
            sb.append("[PROFILE] tick=").append(tick)
                    .append(" world=").append(totalMs).append("ms")
                    .append(" machines=").append(machineCount)
                    .append(inWindow ? " window=ON" : " window=OFF");

            // print GC delta only when meaningful
            if (dGcCount > 0 || dGcTimeMs > 0) {
                sb.append(" gc=+").append(dGcCount).append(" / +").append(dGcTimeMs).append("ms");
            }

            sb.append(" top=");

            boolean first = true;
            for (int i = 0; i < limit; i++) {
                int idx = topIdx[i];
                if (idx < 0) continue;

                long clsMs = totalNs[idx] / 1_000_000L;
                long avgUs = (count[idx] > 0) ? (totalNs[idx] / count[idx]) / 1_000L : 0;
                long maxUs = maxNs[idx] / 1_000L;

                String name = classes[idx].getSimpleName();
                if (name == null || name.isBlank()) name = classes[idx].getName();

                if (!first) sb.append(" | ");
                first = false;

                sb.append(name)
                        .append(":").append(clsMs).append("ms")
                        .append(" n=").append(count[idx])
                        .append(" avg=").append(avgUs).append("us")
                        .append(" max=").append(maxUs).append("us");
            }

            System.out.println(sb);
        }
    }

    private static long getTotalGcCount() {
        long sum = 0L;
        List<GarbageCollectorMXBean> beans = ManagementFactory.getGarbageCollectorMXBeans();
        for (GarbageCollectorMXBean b : beans) {
            long c = b.getCollectionCount();
            if (c >= 0) sum += c;
        }
        return sum;
    }

    private static long getTotalGcTimeMs() {
        long sum = 0L;
        List<GarbageCollectorMXBean> beans = ManagementFactory.getGarbageCollectorMXBeans();
        for (GarbageCollectorMXBean b : beans) {
            long t = b.getCollectionTime();
            if (t >= 0) sum += t;
        }
        return sum;
    }
}
