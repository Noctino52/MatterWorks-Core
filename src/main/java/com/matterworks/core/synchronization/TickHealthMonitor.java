package com.matterworks.core.synchronization;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.ArrayList;
import java.util.List;

/**
 * Measures "lateness" vs scheduled tick time and gap between ticks.
 * Useful to diagnose OS timer jitter / oversleep vs GC.
 */
class TickHealthMonitor {

    private final List<GarbageCollectorMXBean> gcBeans;
    private final MemoryMXBean memoryBean;

    private final long expectedTickMs;
    private final long pauseThresholdMs;

    private long lastEndNs = System.nanoTime();
    private long lastGcCount = 0;
    private long lastGcTimeMs = 0;

    // Rate-limit pause logs to avoid spam
    private long lastPauseLogMs = 0L;
    private static final long PAUSE_LOG_MIN_INTERVAL_MS = 500L;

    TickHealthMonitor(long expectedTickMs, long pauseThresholdMs) {
        this.expectedTickMs = Math.max(1L, expectedTickMs);
        this.pauseThresholdMs = Math.max(1L, pauseThresholdMs);

        this.gcBeans = new ArrayList<>(ManagementFactory.getGarbageCollectorMXBeans());
        this.memoryBean = ManagementFactory.getMemoryMXBean();

        snapshotGc(); // initialize deltas
    }

    /**
     * @param scheduledStartNs The target scheduled time for this tick start (from the loop scheduler).
     */
    void onTickStart(long tick, long scheduledStartNs) {
        long nowNs = System.nanoTime();

        long gapMs = (nowNs - lastEndNs) / 1_000_000L;
        long latenessMs = (nowNs - scheduledStartNs) / 1_000_000L;

        // Only consider "pause" when the loop was waiting and started late.
        // We log using the gap (idle time) as primary signal.
        if (gapMs >= pauseThresholdMs) {
            maybeLogPause(tick, gapMs, latenessMs);
        }
    }

    void onTickEnd(long tick, long tickElapsedMs) {
        lastEndNs = System.nanoTime();
    }

    private void maybeLogPause(long tick, long gapMs, long latenessMs) {
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastPauseLogMs < PAUSE_LOG_MIN_INTERVAL_MS) return;
        lastPauseLogMs = nowMs;

        GcSnapshot gc = snapshotGcDelta();
        HeapSnapshot heap = snapshotHeap();

        // If GC time delta > 0 during the window, it's likely GC. Otherwise oversleep/stall.
        String kind = (gc.deltaTimeMs > 0) ? "GC" : "STALL";

        System.out.println(
                "[PAUSE] tick=" + tick +
                        " kind=" + kind +
                        " gap=" + gapMs + "ms" +
                        " (expected~" + expectedTickMs + "ms)" +
                        " lateness=" + latenessMs + "ms" +
                        " gcCountΔ=" + gc.deltaCount +
                        " gcTimeΔ=" + gc.deltaTimeMs + "ms" +
                        " heapUsed=" + heap.usedMb + "MB" +
                        " heapCommitted=" + heap.committedMb + "MB" +
                        " heapMax=" + heap.maxMb + "MB"
        );
    }

    private void snapshotGc() {
        long c = 0;
        long t = 0;
        for (GarbageCollectorMXBean b : gcBeans) {
            long bc = b.getCollectionCount();
            long bt = b.getCollectionTime();
            if (bc > 0) c += bc;
            if (bt > 0) t += bt;
        }
        lastGcCount = c;
        lastGcTimeMs = t;
    }

    private GcSnapshot snapshotGcDelta() {
        long c = 0;
        long t = 0;
        for (GarbageCollectorMXBean b : gcBeans) {
            long bc = b.getCollectionCount();
            long bt = b.getCollectionTime();
            if (bc > 0) c += bc;
            if (bt > 0) t += bt;
        }

        long dc = c - lastGcCount;
        long dt = t - lastGcTimeMs;

        lastGcCount = c;
        lastGcTimeMs = t;

        return new GcSnapshot(dc, dt);
    }

    private HeapSnapshot snapshotHeap() {
        var usage = memoryBean.getHeapMemoryUsage();
        long used = usage.getUsed();
        long committed = usage.getCommitted();
        long max = usage.getMax();

        return new HeapSnapshot(
                toMb(used),
                toMb(committed),
                toMb(max)
        );
    }

    private static long toMb(long bytes) {
        if (bytes <= 0) return 0;
        return bytes / (1024L * 1024L);
    }

    private record GcSnapshot(long deltaCount, long deltaTimeMs) {}
    private record HeapSnapshot(long usedMb, long committedMb, long maxMb) {}
}
