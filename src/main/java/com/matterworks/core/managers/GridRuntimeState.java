package com.matterworks.core.managers;

import com.matterworks.core.common.GridPosition;
import com.matterworks.core.domain.machines.base.PlacedMachine;
import com.matterworks.core.domain.matter.MatterColor;
import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.model.PlotUnlockState;
import com.matterworks.core.ui.MariaDBAdapter;
import com.matterworks.core.ui.ServerConfig;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

final class GridRuntimeState {

    final MariaDBAdapter repository;

    volatile ServerConfig serverConfig;

    // --- RUNTIME WORLD STATE ---
    final Map<UUID, PlayerProfile> activeProfileCache = new ConcurrentHashMap<>();
    final Map<UUID, Map<GridPosition, PlacedMachine>> playerGrids = new ConcurrentHashMap<>();
    final Map<UUID, Map<GridPosition, MatterColor>> playerResources = new ConcurrentHashMap<>();
    final Map<UUID, PlotUnlockState> plotUnlockCache = new ConcurrentHashMap<>();

    /**
     * A concurrent "set" of machines to tick. We rely on PlacedMachine default identity hashCode/equals.
     * This removes:
     * - global synchronized list lock
     * - O(n) contains() checks on every placement
     */
    final ConcurrentHashMap<PlacedMachine, Boolean> tickingMachines = new ConcurrentHashMap<>();

    /**
     * Dedicated pool for ticking machines in parallel.
     * Keep it bounded to avoid killing EDT when running the Swing mock app.
     * On the real server, this uses CPU cores efficiently.
     */
    final ExecutorService tickPool;

    // --- ACTIVITY / SLEEPING ---
    final Map<UUID, Long> lastActivityMs = new ConcurrentHashMap<>();
    final Set<UUID> sleepingPlayers = ConcurrentHashMap.newKeySet();
    volatile int minutesToInactive = 5;

    // --- MAINTENANCE ---
    long lastSweepTick = 0;

    static final class PlaytimeCacheEntry {
        volatile long seconds;
        volatile long lastFetchMs;
    }

    final Map<UUID, PlaytimeCacheEntry> playtimeCache = new ConcurrentHashMap<>();

    // Cache of persisted bonus (plots.void_itemcap_extra)
    final Map<UUID, Integer> voidItemCapExtraCache = new ConcurrentHashMap<>();

    // ==========================================================
    // GLOBAL OVERCLOCK (server-wide, real-time)
    // ==========================================================
    volatile long globalOverclockEndEpochMs = 0L;
    volatile double globalOverclockMultiplier = 1.0;
    volatile long globalOverclockLastDurationSeconds = 0L;

    GridRuntimeState(MariaDBAdapter repository) {
        this.repository = repository;
        this.serverConfig = repository.loadServerConfig();
        reloadMinutesToInactive();
        reloadGlobalOverclockFromDb();

        int cores = Runtime.getRuntime().availableProcessors();
        // Keep one core free for UI / networking where possible
        int threads = Math.max(1, cores - 1);

        this.tickPool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "mw-tick-worker");
            t.setDaemon(true);
            return t;
        });
    }

    ServerConfig getServerConfig() {
        ServerConfig cfg = serverConfig;
        if (cfg == null) {
            cfg = repository.loadServerConfig();
            serverConfig = cfg;
        }
        return cfg;
    }

    long getPlaytimeSecondsCached(UUID ownerId) {
        if (ownerId == null) return 0L;

        PlaytimeCacheEntry e = playtimeCache.computeIfAbsent(ownerId, k -> new PlaytimeCacheEntry());
        long now = System.currentTimeMillis();

        // refresh every 5 seconds
        if (now - e.lastFetchMs > 5000L) {
            long v = repository.getTotalPlaytimeSeconds(ownerId);
            e.seconds = Math.max(0L, v);
            e.lastFetchMs = now;
        }

        return e.seconds;
    }

    void reloadServerConfig() {
        this.serverConfig = repository.loadServerConfig();
    }

    void reloadMinutesToInactive() {
        this.minutesToInactive = Math.max(1, repository.loadMinutesToInactive());
    }

    void touchPlayer(UUID ownerId) {
        if (ownerId == null) return;

        lastActivityMs.put(ownerId, System.currentTimeMillis());

        // If player was sleeping, wake them and re-add their machines to ticking set
        if (sleepingPlayers.remove(ownerId)) {
            Map<GridPosition, PlacedMachine> grid = playerGrids.get(ownerId);
            if (grid != null && !grid.isEmpty()) {
                for (PlacedMachine pm : new HashSet<>(grid.values())) {
                    if (pm == null) continue;
                    tickingMachines.put(pm, Boolean.TRUE);
                }
            }
        }
    }

    void sweepInactivePlayers(long currentTick) {
        if (currentTick - lastSweepTick < 100) return;
        lastSweepTick = currentTick;

        long now = System.currentTimeMillis();
        long threshold = minutesToInactive * 60_000L;

        for (UUID ownerId : playerGrids.keySet()) {
            long last = lastActivityMs.getOrDefault(ownerId, 0L);
            if (last == 0L) continue;

            if (!sleepingPlayers.contains(ownerId) && (now - last) >= threshold) {
                sleepingPlayers.add(ownerId);

                // Remove all machines for that owner from ticking set
                tickingMachines.keySet().removeIf(m -> m != null && ownerId.equals(m.getOwnerId()));

                repository.closePlayerSession(ownerId);
            }
        }
    }

    PlayerProfile getCachedProfile(UUID uuid) {
        if (uuid == null) return null;
        return activeProfileCache.computeIfAbsent(uuid, repository::loadPlayerProfile);
    }

    boolean checkItemCap(UUID playerId, String itemId, int incomingAmount) {
        int inInventory = repository.getInventoryItemCount(playerId, itemId);

        Map<GridPosition, PlacedMachine> placed = playerGrids.get(playerId);
        long placedCount = (placed != null)
                ? placed.values().stream()
                .filter(Objects::nonNull)
                .distinct()
                .filter(m -> m.getTypeId().equals(itemId))
                .count()
                : 0;

        long total = inInventory + placedCount + incomingAmount;

        if ("nexus_core".equals(itemId) && total > 1) return false;

        if ("drill".equals(itemId)) {
            Map<GridPosition, MatterColor> veins = playerResources.get(playerId);
            if (total > (veins != null ? veins.size() : 0)) return false;
        }

        return true;
    }

    int getPlacedItemCount(UUID ownerId) {
        if (ownerId == null) return 0;

        Map<GridPosition, PlacedMachine> grid = playerGrids.get(ownerId);
        if (grid != null && !grid.isEmpty()) {
            return (int) grid.values().stream()
                    .filter(Objects::nonNull)
                    .distinct()
                    .count();
        }

        // fallback when plot not loaded
        return repository.getPlotItemsPlaced(ownerId);
    }

    int getPersistedVoidItemCapExtra(UUID ownerId) {
        if (ownerId == null) return 0;

        Integer cached = voidItemCapExtraCache.get(ownerId);
        if (cached != null) return Math.max(0, cached);

        int fromDb = 0;
        try {
            fromDb = repository.getPlotVoidItemCapExtra(ownerId);
        } catch (Throwable ignored) {}

        fromDb = Math.max(0, fromDb);
        voidItemCapExtraCache.put(ownerId, fromDb);
        return fromDb;
    }

    void setPersistedVoidItemCapExtra(UUID ownerId, int newValue) {
        if (ownerId == null) return;
        voidItemCapExtraCache.put(ownerId, Math.max(0, newValue));
    }

    boolean canPlaceAnotherItem(UUID ownerId) {
        PlayerProfile p = getCachedProfile(ownerId);
        if (p != null && p.isAdmin()) return true;

        int cap = getEffectiveItemPlacedOnPlotCap(ownerId);
        int placed = getPlacedItemCount(ownerId);

        if (placed >= cap) {
            System.out.println("CAP REACHED: owner=" + ownerId
                    + " placed=" + placed + " cap=" + cap
                    + " -> remove something first!");
            return false;
        }
        return true;
    }

    int getEffectiveItemPlacedOnPlotCap(UUID ownerId) {
        int base = repository.getDefaultItemPlacedOnPlotCap();
        if (base <= 0) base = 1;

        int prestigeStep = Math.max(0, repository.getItemCapIncreaseStep());
        int max = repository.getMaxItemPlacedOnPlotCap();
        if (max <= 0) max = Integer.MAX_VALUE;

        int prestige = 0;
        PlayerProfile p = getCachedProfile(ownerId);
        if (p != null) prestige = Math.max(0, p.getPrestigeLevel());

        int voidExtra = getPersistedVoidItemCapExtra(ownerId);

        long computed = (long) base + (long) prestigeStep * prestige + (long) voidExtra;
        if (computed > Integer.MAX_VALUE) computed = Integer.MAX_VALUE;

        int cap = (int) computed;
        cap = Math.min(cap, max);
        cap = Math.max(1, cap);
        return cap;
    }

    // ==========================================================
    // GLOBAL OVERCLOCK (server-wide, real-time)
    // ==========================================================

    void reloadGlobalOverclockFromDb() {
        try {
            long endMs = Math.max(0L, repository.getGlobalOverclockEndEpochMs());
            double mult = repository.getGlobalOverclockMultiplier();
            long lastDur = Math.max(0L, repository.getGlobalOverclockLastDurationSeconds());

            if (Double.isNaN(mult) || Double.isInfinite(mult) || mult <= 0.0) mult = 1.0;

            globalOverclockEndEpochMs = endMs;
            globalOverclockMultiplier = mult;
            globalOverclockLastDurationSeconds = lastDur;
        } catch (Throwable t) {
            globalOverclockEndEpochMs = 0L;
            globalOverclockMultiplier = 1.0;
            globalOverclockLastDurationSeconds = 0L;
        }
    }

    void setGlobalOverclockStateCached(long endEpochMs, double multiplier, long lastDurationSeconds) {
        long endMs = Math.max(0L, endEpochMs);
        double mult = multiplier;
        if (Double.isNaN(mult) || Double.isInfinite(mult) || mult <= 0.0) mult = 1.0;
        long lastDur = Math.max(0L, lastDurationSeconds);

        globalOverclockEndEpochMs = endMs;
        globalOverclockMultiplier = mult;
        globalOverclockLastDurationSeconds = lastDur;
    }

    double getGlobalOverclockMultiplierNow() {
        long endMs = globalOverclockEndEpochMs;
        if (endMs <= 0L) return 1.0;

        long now = System.currentTimeMillis();
        if (now >= endMs) return 1.0;

        double mult = globalOverclockMultiplier;
        if (Double.isNaN(mult) || Double.isInfinite(mult) || mult <= 0.0) return 1.0;
        return mult;
    }

    long getGlobalOverclockRemainingSeconds() {
        long endMs = globalOverclockEndEpochMs;
        if (endMs <= 0L) return 0L;

        long now = System.currentTimeMillis();
        long diffMs = endMs - now;
        if (diffMs <= 0L) return 0L;

        return (long) Math.ceil(diffMs / 1000.0);
    }

    long getGlobalOverclockLastDurationSecondsCached() {
        return Math.max(0L, globalOverclockLastDurationSeconds);
    }
}
