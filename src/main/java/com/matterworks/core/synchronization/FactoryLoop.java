package com.matterworks.core.synchronization;

import com.matterworks.core.managers.GridManager;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class FactoryLoop {

    private static final long TICK_MS = 50L; // 20 TPS

    private final GridManager gridManager;
    private final ScheduledExecutorService scheduler;
    private final AtomicLong currentTick = new AtomicLong(0);

    private volatile boolean running = false;

    // light telemetry
    private volatile long lastWarnMs = 0L;

    // NEW: pause/GC monitor
    private final TickHealthMonitor healthMonitor;

    public FactoryLoop(GridManager gridManager) {
        this.gridManager = gridManager;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mw-factory-loop");
            t.setDaemon(true);

            // Keep EDT responsive in the Swing mock app
            try {
                t.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            } catch (Throwable ignored) {}

            return t;
        });

        // Consider a pause anything larger than ~2 ticks.
        // You can tune this (e.g. 120ms, 150ms, 200ms).
        this.healthMonitor = new TickHealthMonitor(TICK_MS, 120L);
    }

    public void start() {
        if (running) return;
        running = true;

        // scheduleWithFixedDelay does NOT try to "catch up" if a tick is slow.
        scheduler.scheduleWithFixedDelay(() -> {
            if (!running) return;

            long tick = currentTick.incrementAndGet();
            healthMonitor.onTickStart(tick);

            long startNs = System.nanoTime();
            try {
                gridManager.tick(tick);
            } catch (Throwable t) {
                System.err.println("CRITICAL: Exception in Factory Loop!");
                t.printStackTrace();
            } finally {
                long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
                healthMonitor.onTickEnd(tick, elapsedMs);

                // Warn if tick is taking too long (once per second max)
                if (elapsedMs > TICK_MS) {
                    long now = System.currentTimeMillis();
                    if (now - lastWarnMs > 1000L) {
                        lastWarnMs = now;
                        System.out.println("[PERF] Tick over budget: " + elapsedMs + "ms (budget=" + TICK_MS + "ms)");
                    }
                }
            }
        }, 0, TICK_MS, TimeUnit.MILLISECONDS);

        System.out.println("Factory Loop Started.");
    }

    public void stop() {
        running = false;
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public boolean isRunning() {
        return running;
    }

    public long getCurrentTick() {
        return currentTick.get();
    }
}
