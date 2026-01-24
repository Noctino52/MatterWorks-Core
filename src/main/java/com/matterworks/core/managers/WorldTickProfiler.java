package com.matterworks.core.managers;

import java.util.*;

/**
 * Extremely lightweight tick profiler.
 *
 * Default behavior:
 * - sample once per second (tick % 20 == 0) to avoid overhead.
 *
 * Debug behavior:
 * - when an over-budget tick is detected, we "arm" a profiling window for the next N ticks,
 *   so we can reliably catch the offender even if spikes happen on non-sampled ticks.
 */
public final class WorldTickProfiler {

    private WorldTickProfiler() {}

    /** Profile window end tick (inclusive). Disabled when < 0. */
    private static volatile long profileWindowUntilTick = -1;

    /** Throttle profile printing to avoid log spam. */
    private static volatile long lastProfilePrintMs = 0;

    /** Sample once per second at nominal 20 TPS (tick-based), plus any armed window. */
    public static boolean shouldSample(long tick) {
        return (tick % 20L == 0L) || (profileWindowUntilTick >= 0 && tick <= profileWindowUntilTick);
    }

    /** Arms a profiling window for the next {@code durationTicks} ticks. */
    public static void triggerWindow(long currentTick, long durationTicks) {
        long until = currentTick + Math.max(1, durationTicks);
        long prev = profileWindowUntilTick;
        if (prev < until) profileWindowUntilTick = until;
    }

    public static Sample beginSample(long tick) {
        return new Sample(tick);
    }

    public static final class Sample {
        private final long tick;
        private final Map<Class<?>, Stat> stats = new HashMap<>(64);

        private Sample(long tick) {
            this.tick = tick;
        }

        public void record(Class<?> cls, long dtNs) {
            if (cls == null) return;
            Stat s = stats.get(cls);
            if (s == null) {
                s = new Stat();
                stats.put(cls, s);
            }
            s.count++;
            s.totalNs += dtNs;
            if (dtNs > s.maxNs) s.maxNs = dtNs;
        }

        public void end(long worldTickNs, int machineCount) {
            if (stats.isEmpty()) return;

            long totalMs = worldTickNs / 1_000_000L;

            boolean inWindow = (profileWindowUntilTick >= 0 && tick <= profileWindowUntilTick);

            // Normal mode: print only if heavy.
            // Debug window: print also when moderately heavy, but throttle.
            long thresholdMs = inWindow ? 10L : 40L;
            if (totalMs < thresholdMs) return;

            long now = System.currentTimeMillis();
            if (inWindow && (now - lastProfilePrintMs) < 500L) return; // throttle
            lastProfilePrintMs = now;

            List<Map.Entry<Class<?>, Stat>> list = new ArrayList<>(stats.entrySet());
            list.sort(Comparator.comparingLong((Map.Entry<Class<?>, Stat> e) -> e.getValue().totalNs).reversed());

            StringBuilder sb = new StringBuilder(512);
            sb.append("[PROFILE] tick=").append(tick)
                    .append(" world=").append(totalMs).append("ms")
                    .append(" machines=").append(machineCount)
                    .append(inWindow ? " window=ON" : " window=OFF")
                    .append(" top=");

            int limit = Math.min(10, list.size());
            for (int i = 0; i < limit; i++) {
                Map.Entry<Class<?>, Stat> e = list.get(i);
                Stat s = e.getValue();

                long clsMs = s.totalNs / 1_000_000L;
                long avgUs = (s.count > 0) ? (s.totalNs / s.count) / 1_000L : 0;
                long maxUs = s.maxNs / 1_000L;

                String name = e.getKey().getSimpleName();
                if (name == null || name.isBlank()) name = e.getKey().getName();

                if (i > 0) sb.append(" | ");
                sb.append(name)
                        .append(":").append(clsMs).append("ms")
                        .append(" n=").append(s.count)
                        .append(" avg=").append(avgUs).append("us")
                        .append(" max=").append(maxUs).append("us");
            }

            System.out.println(sb);
        }
    }

    private static final class Stat {
        int count;
        long totalNs;
        long maxNs;
    }
}
