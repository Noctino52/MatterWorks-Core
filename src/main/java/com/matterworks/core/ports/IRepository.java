package com.matterworks.core.ports;

import com.matterworks.core.domain.player.LinkCode;
import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.model.PlotObject;

import java.util.List;
import java.util.UUID;

public interface IRepository {
    void loadRecipes();
    void fetchTransactions();

    void savePlayerProfile(PlayerProfile p);
    PlayerProfile loadPlayerProfile(UUID p);

    List<PlotObject> loadPlotMachines(UUID plotOwnerId);
    void savePlotMachines(UUID plotOwnerId, List<PlotObject> machines);

    // NEW: Metodo richiesto per il tasto destro
    void deleteMachine(Long dbId);

    void saveWebLinkCode(UUID p, LinkCode code);
}