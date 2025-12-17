package com.matterworks.core.infrastructure;

import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.database.dao.PlayerDAO;
import com.matterworks.core.database.dao.PlotDAO;
import com.matterworks.core.domain.machines.PlacedMachine;
import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.model.PlotObject;
import com.matterworks.core.ports.IRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

public class MariaDBAdapter implements IRepository {

    private final DatabaseManager dbManager;
    private final PlayerDAO playerDAO;
    private final PlotDAO plotDAO;

    public MariaDBAdapter(DatabaseManager dbManager) {
        this.dbManager = dbManager;
        this.playerDAO = new PlayerDAO(dbManager);
        this.plotDAO = new PlotDAO(dbManager);
    }

    @Override
    public PlayerProfile loadPlayerProfile(UUID uuid) {
        return playerDAO.load(uuid);
    }

    @Override
    public void savePlayerProfile(PlayerProfile profile) {
        playerDAO.save(profile);
    }

    @Override
    public List<PlotObject> loadPlotMachines(UUID ownerId) {
        return plotDAO.loadMachines(ownerId);
    }

    @Override
    public Long createMachine(UUID ownerId, PlacedMachine machine) {
        String jsonMeta = machine.serialize().toString();
        // Chiama il nuovo metodo nel DAO
        return plotDAO.insertMachine(
                ownerId,
                machine.getTypeId(),
                machine.getPos().x(),
                machine.getPos().y(),
                machine.getPos().z(),
                jsonMeta
        );
    }

    @Override
    public void deleteMachine(Long dbId) {
        plotDAO.removeMachine(dbId);
    }

    @Override
    public void updateMachinesMetadata(List<PlacedMachine> machines) {
        if (machines == null || machines.isEmpty()) return;
        String sql = "UPDATE plot_machines SET metadata = ? WHERE id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (PlacedMachine pm : machines) {
                if (pm.getDbId() == null) continue;
                stmt.setString(1, pm.serialize().toString());
                stmt.setLong(2, pm.getDbId());
                stmt.addBatch();
            }
            stmt.executeBatch();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}