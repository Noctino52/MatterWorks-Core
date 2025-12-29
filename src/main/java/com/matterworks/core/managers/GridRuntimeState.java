// FILE: src/main/java/com/matterworks/core/managers/GridRuntimeState.java
package com.matterworks.core.managers;

import com.matterworks.core.common.GridPosition;
import com.matterworks.core.domain.machines.base.PlacedMachine;
import com.matterworks.core.domain.matter.MatterColor;
import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.model.PlotUnlockState;
import com.matterworks.core.ports.IRepository;
import com.matterworks.core.ui.MariaDBAdapter;
import com.matterworks.core.ui.ServerConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class GridRuntimeState {

    final IRepository repository;

    volatile ServerConfig serverConfig;

    // --- RUNTIME WORLD STATE ---
    final Map<UUID, PlayerProfile> activeProfileCache = new ConcurrentHashMap<>();
    final Map<UUID, Map<GridPosition, PlacedMachine>> playerGrids = new ConcurrentHashMap<>();
    final Map<UUID, Map<GridPosition, MatterColor>> playerResources = new ConcurrentHashMap<>();
    final Map<UUID, PlotUnlockState> plotUnlockCache = new ConcurrentHashMap<>();

    final List<PlacedMachine> tickingMachines = Collections.synchronizedList(new ArrayList<>());

    // --- ACTIVITY / SLEEPING ---
    final Map<UUID, Long> lastActivityMs = new ConcurrentHashMap<>();
    final Set<UUID> sleepingPlayers = ConcurrentHashMap.newKeySet();
    volatile int minutesToInactive = 5;

    // --- MAINTENANCE ---
    long lastSweepTick = 0;

    GridRuntimeState(IRepository repository) {
        this.repository = repository;
        this.serverConfig = repository.loadServerConfig();
        reloadMinutesToInactive();
    }

    ServerConfig getServerConfig() {
        ServerConfig cfg = serverConfig;
        if (cfg == null) {
            cfg = repository.loadServerConfig();
            serverConfig = cfg;
        }
        return cfg;
    }

    void reloadServerConfig() {
        this.serverConfig = repository.loadServerConfig();
    }

    // ==========================================================
    // CONFIG: MinutesToInactive
    // ==========================================================
    void reloadMinutesToInactive() {
        if (repository instanceof MariaDBAdapter a) {
            this.minutesToInactive = Math.max(1, a.loadMinutesToInactive());
        } else {
            this.minutesToInactive = 5;
        }
    }

    // ==========================================================
    // ACTIVITY / SLEEPING
    // ==========================================================
    void touchPlayer(UUID ownerId) {
        if (ownerId == null) return;

        lastActivityMs.put(ownerId, System.currentTimeMillis());

        if (sleepingPlayers.remove(ownerId)) {
            Map<GridPosition, PlacedMachine> grid = playerGrids.get(ownerId);
            if (grid != null && !grid.isEmpty()) {
                synchronized (tickingMachines) {
                    for (PlacedMachine pm : new java.util.HashSet<>(grid.values())) {
                        if (pm == null) continue;
                        if (!tickingMachines.contains(pm)) tickingMachines.add(pm);
                    }
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
                synchronized (tickingMachines) {
                    tickingMachines.removeIf(m -> m != null && ownerId.equals(m.getOwnerId()));
                }
                if (repository instanceof MariaDBAdapter a) {
                    a.closePlayerSession(ownerId);
                }
            }
        }
    }

    // ==========================================================
    // PROFILES
    // ==========================================================
    PlayerProfile getCachedProfile(UUID uuid) {
        if (uuid == null) return null;
        return activeProfileCache.computeIfAbsent(uuid, repository::loadPlayerProfile);
    }

    // ==========================================================
    // CAPS (logica identica)
    // ==========================================================
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

        if (itemId.equals("nexus_core") && total > 1) return false;

        if (itemId.equals("drill_mk1")) {
            Map<GridPosition, MatterColor> veins = playerResources.get(playerId);
            if (total > (veins != null ? veins.size() : 0)) return false;
        }

        return true;
    }

    boolean canPlaceAnotherItem(UUID ownerId) {
        PlayerProfile p = getCachedProfile(ownerId);
        if (p != null && p.isAdmin()) return true;

        int cap = getEffectiveItemPlacedOnPlotCap(ownerId);
        int placed = repository.getPlotItemsPlaced(ownerId);

        if (placed >= cap) {
            System.out.println("⚠️ CAP RAGGIUNTO: plot owner=" + ownerId
                    + " item_placed=" + placed + " cap=" + cap
                    + " -> non puoi piazzare altri item, rimuovi qualcosa!");
            return false;
        }
        return true;
    }

    int getEffectiveItemPlacedOnPlotCap(UUID ownerId) {
        int base = repository.getDefaultItemPlacedOnPlotCap();
        if (base <= 0) base = 1;

        int step = Math.max(0, repository.getItemCapIncreaseStep());
        int max = repository.getMaxItemPlacedOnPlotCap();
        if (max <= 0) max = Integer.MAX_VALUE;

        int prestige = 0;
        PlayerProfile p = getCachedProfile(ownerId);
        if (p != null) prestige = Math.max(0, p.getPrestigeLevel());

        long raw = (long) base + (long) prestige * (long) step;
        int cap = raw > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) raw;

        cap = Math.min(cap, max);
        return Math.max(1, cap);
    }
}
