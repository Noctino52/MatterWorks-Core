package com.matterworks.core.ports;

import com.matterworks.core.domain.machines.PlacedMachine;
import com.matterworks.core.domain.matter.MatterColor;
import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.infrastructure.ServerConfig;
import com.matterworks.core.model.PlotObject;
import com.matterworks.core.common.GridPosition;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface IRepository {

    // --- CONFIG ---
    ServerConfig loadServerConfig();

    // --- PLAYER & PROFILE ---
    PlayerProfile loadPlayerProfile(UUID uuid);
    void savePlayerProfile(PlayerProfile profile);
    void deletePlayerFull(UUID uuid);

    // --- PLOT DATA ---
    List<PlotObject> loadPlotMachines(UUID ownerId);
    Long createMachine(UUID ownerId, PlacedMachine machine);
    void deleteMachine(Long dbId);
    void updateMachinesMetadata(List<PlacedMachine> machines);
    void clearPlotData(UUID ownerId);
    Long getPlotId(UUID ownerId);

    // --- RISORSE ---
    void saveResource(Long plotId, int x, int z, MatterColor type);
    Map<GridPosition, MatterColor> loadResources(Long plotId);

    // --- INVENTARIO ---
    int getInventoryItemCount(UUID ownerId, String itemId);
    void modifyInventoryItem(UUID ownerId, String itemId, int delta);
    List<PlayerProfile> getAllPlayers();
}