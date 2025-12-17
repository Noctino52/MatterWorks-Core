package com.matterworks.core.ports;

import com.matterworks.core.domain.machines.PlacedMachine;
import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.model.PlotObject;

import java.util.List;
import java.util.UUID;

public interface IRepository {

    PlayerProfile loadPlayerProfile(UUID uuid);
    void savePlayerProfile(PlayerProfile profile);

    List<PlotObject> loadPlotMachines(UUID ownerId);
    Long createMachine(UUID ownerId, PlacedMachine machine);
    void deleteMachine(Long dbId);
    void updateMachinesMetadata(List<PlacedMachine> machines);

    void clearPlotData(UUID ownerId); // Metodo aggiunto nel passo precedente

    // --- NUOVO: CONTROLLO INVENTARIO PER BAILOUT ---
    int getInventoryItemCount(UUID ownerId, String itemId);
}