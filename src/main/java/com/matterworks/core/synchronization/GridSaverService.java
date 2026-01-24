package com.matterworks.core.synchronization;

import com.matterworks.core.common.GridPosition;
import com.matterworks.core.domain.machines.base.PlacedMachine;
import com.matterworks.core.managers.GridManager;
import com.matterworks.core.ui.MariaDBAdapter;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Incremental, write-behind autosaver.
 *
 * Key properties:
 * - Never performs DB I/O on the simulation tick thread.
 * - Saves only plots marked dirty (deduped).
 * - Applies retry backoff on failures.
 *
 * PERFORMANCE FIX:
 * - Avoids allocating a full HashMap snapshot of the grid (no new HashMap<>(g)).
 * - Reuses IdentityHashMap + ArrayList buffers to reduce GC pressure.
 */
public class GridSaverService implements AutoCloseable {

    // Keep batch small to avoid DB spikes and GC spikes.
    private static final int MAX_PLOTS_PER_FLUSH = 2;

    // Retry backoff (ms): 1s, 2s, 4s, 8s ... capped.
    private static final long RETRY_BASE_MS = 1_000L;
    private static final long RETRY_MAX_MS = 30_000L;

    private final GridManager gridManager;
    private final MariaDBAdapter repository;

    // Dedup: a plotId can be enqueued multiple times but saved once.
    private final ConcurrentHashMap<UUID, Boolean> dirtyPlots = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<UUID> dirtyQueue = new ConcurrentLinkedQueue<>();

    // Retry state per plot
    private final ConcurrentHashMap<UUID, RetryState> retryState = new ConcurrentHashMap<>();

    // Single saver thread (prevents concurrent writes and keeps DB pressure stable).
    private final ScheduledExecutorService saver;

    // Avoid overlapping flush runs
    private final AtomicBoolean flushRunning = new AtomicBoolean(false);

    // (optional) some telemetry
    private volatile long lastLogMs = 0L;

    // Reusable buffers (single saver thread => safe)
    private final IdentityHashMap<PlacedMachine, Boolean> seen = new IdentityHashMap<>(512);
    private final ArrayList<PlacedMachine> dirty = new ArrayList<>(256);

    public GridSaverService(GridManager gridManager, MariaDBAdapter repository) {
        this.gridManager = gridManager;
        this.repository = repository;

        this.saver = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mw-grid-saver");
            t.setDaemon(true);
            try {
                t.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            } catch (Throwable ignored) {}
            return t;
        });
    }

    /**
     * Mark a plot as dirty. This is very cheap and safe to call from tick thread.
     */
    public void markPlotDirty(UUID ownerId) {
        if (ownerId == null) return;

        // Put-if-absent semantics
        if (dirtyPlots.putIfAbsent(ownerId, Boolean.TRUE) == null) {
            dirtyQueue.add(ownerId);
        }
    }

    /**
     * Called by whoever schedules autosave (same as before).
     * IMPORTANT: this method must be cheap and must not do DB work.
     */
    public void autoSaveTask() {
        triggerFlushAsync();
    }

    /**
     * Trigger a flush on the saver thread if one isn't already running.
     */
    private void triggerFlushAsync() {
        if (!flushRunning.compareAndSet(false, true)) return;

        saver.execute(() -> {
            int saved = 0;
            int failed = 0;

            try {
                long nowMs = System.currentTimeMillis();

                for (int i = 0; i < MAX_PLOTS_PER_FLUSH; i++) {
                    UUID ownerId = pollNextEligible(nowMs);
                    if (ownerId == null) break;

                    SaveResult r = saveOwnerPlot(ownerId);
                    if (r == SaveResult.SAVED || r == SaveResult.SKIPPED_EMPTY_OR_NOT_LOADED) {
                        // Remove dirty flag: if it becomes dirty again it will be re-marked.
                        dirtyPlots.remove(ownerId);
                        retryState.remove(ownerId);

                        if (r == SaveResult.SAVED) saved++;
                    } else {
                        failed++;
                        // keep dirty flag, but apply backoff and re-enqueue
                        applyBackoffAndRequeue(ownerId, nowMs);
                    }
                }

                if (saved > 0 || failed > 0) {
                    long now = System.currentTimeMillis();
                    if (now - lastLogMs > 1000L) {
                        lastLogMs = now;
                        System.out.println("💾 AutoSave: saved plots=" + saved + ", failed=" + failed
                                + " (queue=" + dirtyQueue.size() + ", dirty=" + dirtyPlots.size() + ")");
                    }
                }
            } finally {
                flushRunning.set(false);

                // If more work remains, schedule a next flush shortly.
                // This prevents the queue from stalling when a lot of plots are dirty.
                if (!dirtyQueue.isEmpty()) {
                    saver.schedule(this::triggerFlushAsync, 50, TimeUnit.MILLISECONDS);
                }
            }
        });
    }

    /**
     * Take next dirty plot if eligible (not in backoff window).
     */
    private UUID pollNextEligible(long nowMs) {
        for (int tries = 0; tries < 16; tries++) {
            UUID ownerId = dirtyQueue.poll();
            if (ownerId == null) return null;

            RetryState rs = retryState.get(ownerId);
            if (rs == null || nowMs >= rs.nextRetryAtMs) {
                return ownerId;
            }

            // not eligible yet -> requeue to the tail
            dirtyQueue.add(ownerId);
        }
        return null;
    }

    private void applyBackoffAndRequeue(UUID ownerId, long nowMs) {
        RetryState rs = retryState.computeIfAbsent(ownerId, _k -> new RetryState());
        rs.failCount++;

        long delay = RETRY_BASE_MS * (1L << Math.min(rs.failCount, 5)); // cap exponent (1..32)
        if (delay > RETRY_MAX_MS) delay = RETRY_MAX_MS;

        rs.nextRetryAtMs = nowMs + delay;

        // Requeue if still dirty
        dirtyQueue.add(ownerId);
    }

    private SaveResult saveOwnerPlot(UUID ownerId) {
        // PERFORMANCE FIX:
        // Do NOT allocate HashMap snapshots (new HashMap<>(g)).
        // Iterate directly on the concurrent map view.
        Map<GridPosition, PlacedMachine> grid = gridManager.getUnsafeGridView(ownerId);
        if (grid == null || grid.isEmpty()) {
            return SaveResult.SKIPPED_EMPTY_OR_NOT_LOADED;
        }

        // Reuse buffers to avoid GC spikes
        seen.clear();
        dirty.clear();

        // IMPORTANT: grid has lots of duplicates (multi-cell occupancy).
        // Deduplicate by identity to avoid huge dirty lists and heavy DB updates.
        for (PlacedMachine m : grid.values()) {
            if (m == null) continue;
            if (m.getDbId() == null) continue; // not persisted yet (or structural)
            if (!m.isDirty()) continue;

            if (seen.put(m, Boolean.TRUE) == null) {
                dirty.add(m);
            }
        }

        if (dirty.isEmpty()) {
            return SaveResult.SKIPPED_NO_DIRTY;
        }

        try {
            repository.updateMachinesMetadata(dirty);
            for (PlacedMachine m : dirty) m.cleanDirty();
            return SaveResult.SAVED;
        } catch (Exception ex) {
            System.err.println("🚨 AutoSave failed for plot owner " + ownerId + " (will retry with backoff).");
            ex.printStackTrace();
            return SaveResult.FAILED;
        }
    }

    @Override
    public void close() {
        saver.shutdown();
        try {
            if (!saver.awaitTermination(2, TimeUnit.SECONDS)) {
                saver.shutdownNow();
            }
        } catch (InterruptedException e) {
            saver.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private enum SaveResult {
        SAVED,
        SKIPPED_NO_DIRTY,
        SKIPPED_EMPTY_OR_NOT_LOADED,
        FAILED
    }

    private static final class RetryState {
        int failCount = 0;
        long nextRetryAtMs = 0L;
    }
}
