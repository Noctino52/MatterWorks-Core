package com.matterworks.core.infrastructure;

import com.google.gson.JsonParser; // Utile per il parsing
import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.database.dao.PlayerDAO;
import com.matterworks.core.database.dao.PlotDAO;
import com.matterworks.core.database.dao.PlotObjectDAO;
import com.matterworks.core.domain.player.LinkCode;
import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.model.PlotObject;
import com.matterworks.core.ports.IRepository;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class MariaDBAdapter implements IRepository {

    private final PlayerDAO playerDAO;
    private final PlotDAO plotDAO;
    private final PlotObjectDAO plotObjectDAO;

    public MariaDBAdapter(DatabaseManager dbManager) {
        this.playerDAO = new PlayerDAO(dbManager);
        this.plotDAO = new PlotDAO(dbManager);
        this.plotObjectDAO = new PlotObjectDAO(dbManager);
    }

    @Override
    public void savePlayerProfile(PlayerProfile p) {
        playerDAO.save(p);
    }

    @Override
    public PlayerProfile loadPlayerProfile(UUID p) {
        return playerDAO.load(p);
    }

    @Override
    public List<PlotObject> loadPlotMachines(UUID plotOwnerId) {
        Long plotId = plotDAO.findPlotIdByOwner(plotOwnerId);
        if (plotId == null) return Collections.emptyList();

        List<PlotObject> machines = plotObjectDAO.loadPlotMachines(plotId);

        // Piccola fix per convertire stringhe JSON in JsonObject se necessario
        for (PlotObject obj : machines) {
            if (obj.getMetaData() == null && obj.getRawMetaData() != null) {
                try {
                    obj.setMetaData(JsonParser.parseString(obj.getRawMetaData()).getAsJsonObject());
                } catch (Exception e) { /* Ignora errori parse su dati vecchi */ }
            }
        }
        return machines;
    }

    @Override
    public void savePlotMachines(UUID plotOwnerId, List<PlotObject> machines) {
        Long plotId = plotDAO.findPlotIdByOwner(plotOwnerId);
        if (plotId == null) return;

        for (PlotObject obj : machines) {
            obj.setPlotId(plotId);
            plotObjectDAO.placeMachine(obj);
        }
    }

    // NEW: Implementazione delete
    @Override
    public void deleteMachine(Long dbId) {
        plotObjectDAO.deleteMachine(dbId);
    }

    @Override
    public void saveWebLinkCode(UUID p, LinkCode code) {
        // Todo implementation
    }

    @Override
    public void loadRecipes() {}

    @Override
    public void fetchTransactions() {}
}