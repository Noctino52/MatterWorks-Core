package com.matterworks.core.synchronization;

import com.matterworks.core.managers.GridManager;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * High-precision dedicated tick loop.
 *
 * Why:
 * - On Windows, park/sleep can oversleep heavily (timer resolution / power saving).
 * - We combine parkNanos for the "long" portion + spin-wait for the last micro-slice.
 *
 * Config:
 * -Dmw.loop.daemon=true|false (default true)
 * -Dmw.loop.priority=1..10 (default Thread.NORM_PRIORITY)
 * -Dmw.loop.spinTailMs=1 (default 1)  -> how much (ms) to spin at the end of the sleep window
 * -Dmw.loop.resyncLagMs=250 (default 250) -> if behind, resync schedule (avoid catch-up storms)
 */
public final class FactoryLoop {

    private static final long TICK_MS = 50L; // 20 TPS
    private static final long TICK_NS = TICK_MS * 1_000_000L;

    private final GridManager gridManager;
    private final AtomicLong currentTick = new AtomicLong(0);

    private volatile boolean running = false;
    private Thread loopThread;

    private volatile long lastWarnMs = 0L;

    private final TickHealthMonitor healthMonitor;

    private final long spinTailNs;
    private final long resyncLagNs;

    public FactoryLoop(GridManager gridManager) {
        this.gridManager = gridManager;
        this.healthMonitor = new TickHealthMonitor(TICK_MS, 120L);

        long spinTailMs = parseLongClamped(System.getProperty("mw.loop.spinTailMs", "1"), 0, 10, 1);
        this.spinTailNs = spinTailMs * 1_000_000L;

        long resyncLagMs = parseLongClamped(System.getProperty("mw.loop.resyncLagMs", "250"), 0, 5000, 250);
        this.resyncLagNs = resyncLagMs * 1_000_000L;
    }

    public synchronized void start() {
        if (running) return;
        running = true;

        boolean daemon = Boolean.parseBoolean(System.getProperty("mw.loop.daemon", "true"));
        int priority = parseIntClamped(
                System.getProperty("mw.loop.priority", String.valueOf(Thread.NORM_PRIORITY)),
                Thread.MIN_PRIORITY,
                Thread.MAX_PRIORITY,
                Thread.NORM_PRIORITY
        );

        loopThread = new Thread(this::runLoop, "mw-factory-loop");
        loopThread.setDaemon(daemon);

        try {
            loopThread.setPriority(priority);
        } catch (Throwable ignored) {}

        loopThread.start();
        System.out.println("Factory Loop Started.");
    }

    private void runLoop() {
        long nextTickNs = System.nanoTime();

        while (running) {
            // --- wait until scheduled tick time ---
            waitUntil(nextTickNs);

            long startNs = System.nanoTime();
            long tick = currentTick.incrementAndGet();

            // Health monitor now knows the scheduled time -> we can log "lateness"
            healthMonitor.onTickStart(tick, nextTickNs);

            try {
                gridManager.tick(tick);
            } catch (Throwable t) {
                System.err.println("CRITICAL: Exception in Factory Loop!");
                t.printStackTrace();
            } finally {
                long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
                healthMonitor.onTickEnd(tick, elapsedMs);

                if (elapsedMs > TICK_MS) {
                    long nowMs = System.currentTimeMillis();
                    if (nowMs - lastWarnMs > 1000L) {
                        lastWarnMs = nowMs;
                        System.out.println("[PERF] Tick over budget: " + elapsedMs + "ms (budget=" + TICK_MS + "ms)");
                    }
                }
            }

            // --- schedule next tick ---
            long plannedNext = nextTickNs + TICK_NS;
            long nowNs = System.nanoTime();

            // If we are too far behind, resync to avoid "catch-up" storms
            if (nowNs - plannedNext > resyncLagNs) {
                nextTickNs = nowNs;
            } else {
                nextTickNs = plannedNext;
            }
        }
    }

    /**
     * parkNanos for the long slice, then spin for the tail slice.
     * This reduces oversleep when OS timer resolution is coarse.
     */
    private void waitUntil(long targetNs) {
        while (running) {
            long now = System.nanoTime();
            long remaining = targetNs - now;
            if (remaining <= 0) return;

            // If we have enough time, park most of it (leave tail for spin)
            if (remaining > spinTailNs + 200_000L) { // leave a tiny safety margin (0.2ms)
                long parkNs = remaining - spinTailNs;
                LockSupport.parkNanos(parkNs);
                continue;
            }

            // Spin-wait for the last tail
            while (running && System.nanoTime() < targetNs) {
                Thread.onSpinWait();
            }
            return;
        }
    }

    public synchronized void stop() {
        running = false;

        Thread t = loopThread;
        loopThread = null;

        if (t != null) {
            try {
                t.interrupt();
                t.join(2_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable ignored) {}
        }
    }

    public boolean isRunning() {
        return running;
    }

    public long getCurrentTick() {
        return currentTick.get();
    }

    private static int parseIntClamped(String s, int min, int max, int def) {
        try {
            int v = Integer.parseInt(s.trim());
            if (v < min) return min;
            if (v > max) return max;
            return v;
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static long parseLongClamped(String s, long min, long max, long def) {
        try {
            long v = Long.parseLong(s.trim());
            if (v < min) return min;
            if (v > max) return max;
            return v;
        } catch (Throwable ignored) {
            return def;
        }
    }
}
