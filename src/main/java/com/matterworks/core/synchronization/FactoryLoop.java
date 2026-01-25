package com.matterworks.core.synchronization;

import com.matterworks.core.managers.GridManager;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

public class FactoryLoop {

    private final GridManager gridManager;
    private final AtomicLong currentTick = new AtomicLong(0);

    private volatile boolean running = false;
    private Thread loopThread;

    // Target simulation rate
    private static final int TARGET_TPS = 20;
    private static final long TICK_NS = 1_000_000_000L / TARGET_TPS;

    // Avoid spiral-of-death if the server is heavily overloaded
    private static final int MAX_CATCHUP_TICKS_PER_FRAME = 5;

    // Lightweight stats (optional)
    private long statsLastPrintNs = 0L;
    private long statsTickCounter = 0L;
    private static final long STATS_PERIOD_NS = 5_000_000_000L; // 5s

    public FactoryLoop(GridManager gridManager) {
        this.gridManager = gridManager;
    }

    public void start() {
        if (running) return;
        running = true;

        loopThread = new Thread(this::runLoop, "mw-factory-loop");
        loopThread.setDaemon(true);
        loopThread.start();

        System.out.println("🏭 Factory Loop Started. targetTPS=" + TARGET_TPS);
    }

    private void runLoop() {
        long nextTickTime = System.nanoTime();
        long lastStatsNs = nextTickTime;

        statsLastPrintNs = nextTickTime;
        statsTickCounter = 0;

        while (running) {
            long now = System.nanoTime();

            // Sleep/park until the next tick if we're ahead of schedule
            if (now < nextTickTime) {
                LockSupport.parkNanos(nextTickTime - now);
                continue;
            }

            // We're due (or behind). Catch up with a bounded number of ticks.
            int catchUp = 0;
            while (running && now >= nextTickTime && catchUp < MAX_CATCHUP_TICKS_PER_FRAME) {
                long tick = currentTick.incrementAndGet();
                try {
                    gridManager.tick(tick);
                } catch (Throwable t) {
                    System.err.println("CRITICAL: Exception in Factory Loop (tick=" + tick + ")");
                    t.printStackTrace();
                }

                statsTickCounter++;
                nextTickTime += TICK_NS;
                catchUp++;

                now = System.nanoTime();
            }

            // If still behind after max catch-up, resync to avoid infinite backlog.
            if (running && now >= nextTickTime) {
                // Drop backlog: align next tick to "now + one tick"
                nextTickTime = now + TICK_NS;
            }

            // Optional stats print
            long statsNow = System.nanoTime();
            if (statsNow - statsLastPrintNs >= STATS_PERIOD_NS) {
                double seconds = (statsNow - lastStatsNs) / 1_000_000_000.0;
                double tps = seconds > 0 ? (statsTickCounter / seconds) : 0.0;

                System.out.printf("[LOOP] approxTPS=%.2f (ticks=%d in %.2fs)%n",
                        tps, statsTickCounter, seconds);

                statsLastPrintNs = statsNow;
                lastStatsNs = statsNow;
                statsTickCounter = 0;
            }
        }
    }

    public void stop() {
        running = false;

        Thread t = loopThread;
        loopThread = null;

        if (t != null) {
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean isRunning() {
        return running;
    }

    public long getCurrentTick() {
        return currentTick.get();
    }
}
