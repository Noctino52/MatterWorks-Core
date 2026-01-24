package com.matterworks.core.synchronization;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;

/**
 * Detects JVM pauses (GC / STW / OS scheduling) and prints a useful log line.
 *
 * Why:
 * - The per-machine profiler attributes pauses to whichever machine was running.
 * - This monitor prints an explicit "[PAUSE]" line with GC deltas and heap usage,
 *   so you can tell if spikes are real CPU work or external stalls.
 */
public final class TickHealthMonitor {

    private final List<GarbageCollectorMXBean> gcBeans;
    private final MemoryMXBean memoryBean;

    private final long expectedTickMs;
    private final long pauseThresholdMs;

    private long lastWallNs = System.nanoTime();
    private long lastEndNs = lastWallNs;

    private long lastGcCount = 0;
    private long lastGcTimeMs = 0;

    // Rate-limit pause logs to avoid spam
    private long lastPauseLogMs = 0L;
    private static final long PAUSE_LOG_MIN_INTERVAL_MS = 500L;

    public TickHealthMonitor(long expectedTickMs, long pauseThresholdMs) {
        this.expectedTickMs = Math.max(1L, expectedTickMs);
        this.pauseThresholdMs = Math.max(1L, pauseThresholdMs);

        this.gcBeans = new ArrayList<>(ManagementFactory.getGarbageCollectorMXBeans());
        this.memoryBean = ManagementFactory.getMemoryMXBean();

        snapshotGc(); // initialize deltas
    }

    /**
     * Call at the beginning of each tick (before running game logic).
     */
    public void onTickStart(long tick) {
        long nowNs = System.nanoTime();

        // Time passed since last tick end (includes scheduler delay + pauses)
        long gapMs = (nowNs - lastEndNs) / 1_000_000L;

        // Also track "wall clock drift" in case the executor thread is stalled
        long wallMs = (nowNs - lastWallNs) / 1_000_000L;
        lastWallNs = nowNs;

        if (gapMs >= pauseThresholdMs) {
            maybeLogPause(tick, gapMs, wallMs);
        }
    }

    /**
     * Call at the end of each tick.
     */
    public void onTickEnd(long tick, long tickElapsedMs) {
        lastEndNs = System.nanoTime();

        // Optional: you can also log GC activity even without a long pause.
        // Keep it off by default to avoid spam.
    }

    private void maybeLogPause(long tick, long gapMs, long wallMs) {
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastPauseLogMs < PAUSE_LOG_MIN_INTERVAL_MS) return;
        lastPauseLogMs = nowMs;

        GcSnapshot before = snapshotGcDelta();
        HeapSnapshot heap = snapshotHeap();

        // Heuristic classification:
        // If GC time delta > 0 during the pause window, it's very likely a GC pause.
        String kind = (before.deltaTimeMs > 0) ? "GC" : "STALL";

        System.out.println(
                "[PAUSE] tick=" + tick +
                        " kind=" + kind +
                        " gap=" + gapMs + "ms" +
                        " (expected~" + expectedTickMs + "ms)" +
                        " wall=" + wallMs + "ms" +
                        " gcCountΔ=" + before.deltaCount +
                        " gcTimeΔ=" + before.deltaTimeMs + "ms" +
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
        try {
            MemoryUsage heap = memoryBean.getHeapMemoryUsage();
            long used = heap.getUsed();
            long committed = heap.getCommitted();
            long max = heap.getMax();

            return new HeapSnapshot(
                    bytesToMb(used),
                    bytesToMb(committed),
                    bytesToMb(max)
            );
        } catch (Throwable ignored) {
            return new HeapSnapshot(-1, -1, -1);
        }
    }

    private static long bytesToMb(long bytes) {
        if (bytes < 0) return -1;
        return bytes / (1024L * 1024L);
    }

    private record GcSnapshot(long deltaCount, long deltaTimeMs) {}
    private record HeapSnapshot(long usedMb, long committedMb, long maxMb) {}
}
