package com.matterworks.core.synchronization;

import com.matterworks.core.managers.GridManager;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Il Loop di Gioco.
 * - FactoryLoop.
 */
public class FactoryLoop {

    private final GridManager gridManager;
    private final ScheduledExecutorService scheduler;
    private final AtomicLong currentTick = new AtomicLong(0);
    private volatile boolean isRunning = false;

    public FactoryLoop(GridManager gridManager) {
        this.gridManager = gridManager;
        // Thread singolo dedicato al tick del gioco (per evitare race conditions nella logica)
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    public void start() {
        if (isRunning) return;
        isRunning = true;

        // Hytale gira a 20 TPS (Ticks Per Second) -> 50ms per tick
        scheduler.scheduleAtFixedRate(this::runTick, 0, 50, TimeUnit.MILLISECONDS);
        System.out.println("🏭 Factory Loop Started.");
    }

    public void stop() {
        isRunning = false;
        scheduler.shutdown();
    }

    /**
     * runTick
     */
    private void runTick() {
        try {
            long tick = currentTick.incrementAndGet();

            // 1. Tick della griglia (Macchine producono/consumano)
            gridManager.tick(tick);

            // TODO: Qui potremmo chiamare TechManager, CapManager, etc.

        } catch (Exception e) {
            System.err.println("CRITICAL: Exception in Factory Loop!");
            e.printStackTrace();
        }
    }
}