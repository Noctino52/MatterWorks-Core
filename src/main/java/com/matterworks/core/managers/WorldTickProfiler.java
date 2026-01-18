package com.matterworks.core.managers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Extremely lightweight tick profiler.
 * Intended to be used only on sampled ticks (e.g. once per second).
 */
public final class WorldTickProfiler {

    private WorldTickProfiler() {}

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
            // Only print if it's at least moderately heavy, otherwise we spam logs.
            if (totalMs < 40) return;

            List<Map.Entry<Class<?>, Stat>> list = new ArrayList<>(stats.entrySet());
            list.sort(Comparator.comparingLong((Map.Entry<Class<?>, Stat> e) -> e.getValue().totalNs).reversed());

            StringBuilder sb = new StringBuilder(512);
            sb.append("[PROFILE] tick=").append(tick)
                    .append(" world=").append(totalMs).append("ms")
                    .append(" machines=").append(machineCount)
                    .append(" top=");

            int limit = Math.min(8, list.size());
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
