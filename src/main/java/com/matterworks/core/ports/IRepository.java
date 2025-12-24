package com.matterworks.core.ports;

import com.matterworks.core.common.GridPosition;
import com.matterworks.core.domain.machines.base.PlacedMachine;
import com.matterworks.core.domain.matter.MatterColor;
import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.model.PlotObject;
import com.matterworks.core.ui.ServerConfig;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface IRepository {

    // --- TRANSACTION LOGGING ---
    void logTransaction(PlayerProfile player, String actionType, String currency, double amount, String itemId);

    // --- CONFIG ---
    ServerConfig loadServerConfig();

    // ✅ NEW: cap default (server_gamestate.default_item_placed_on_plot)
    int getDefaultItemPlacedOnPlotCap();

    // --- PLAYER & PROFILE ---
    PlayerProfile loadPlayerProfile(UUID uuid);
    void savePlayerProfile(PlayerProfile profile);
    void deletePlayerFull(UUID uuid);
    List<PlayerProfile> getAllPlayers();

    // --- PLOT DATA ---
    List<PlotObject> loadPlotMachines(UUID ownerId);
    Long createMachine(UUID ownerId, PlacedMachine machine);
    void deleteMachine(Long dbId);
    void updateMachinesMetadata(List<PlacedMachine> machines);
    void clearPlotData(UUID ownerId);
    Long getPlotId(UUID ownerId);

    // ✅ NEW: contatore item piazzati nel plot (plots.item_placed)
    int getPlotItemsPlaced(UUID ownerId);

    // --- RISORSE ---
    void saveResource(Long plotId, int x, int z, MatterColor type);
    Map<GridPosition, MatterColor> loadResources(Long plotId);

    // --- INVENTARIO ---
    int getInventoryItemCount(UUID ownerId, String itemId);
    void modifyInventoryItem(UUID ownerId, String itemId, int delta);

    // --- SESSIONS ---
    void openPlayerSession(UUID playerUuid);
    void closePlayerSession(UUID playerUuid);
}
