package com.matterworks.core.synchronization;

import com.matterworks.core.managers.GridManager;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class FactoryLoop {

    private final GridManager gridManager;
    private final ScheduledExecutorService scheduler;
    private final AtomicLong currentTick = new AtomicLong(0);

    private volatile boolean running = false;

    public FactoryLoop(GridManager gridManager) {
        this.gridManager = gridManager;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mw-factory-loop");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        if (running) return;
        running = true;

        // 20 TPS -> 50ms per tick
        scheduler.scheduleAtFixedRate(() -> {
            if (!running) return;

            try {
                long tick = currentTick.incrementAndGet();
                gridManager.tick(tick);
            } catch (Throwable t) {
                System.err.println("CRITICAL: Exception in Factory Loop!");
                t.printStackTrace();
            }
        }, 0, 50, TimeUnit.MILLISECONDS);

        System.out.println("🏭 Factory Loop Started.");
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
