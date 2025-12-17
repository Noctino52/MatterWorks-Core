package com.matterworks.core.infrastructure;

import com.matterworks.core.common.GridPosition;
import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.database.UuidUtils;
import com.matterworks.core.database.dao.PlayerDAO;
import com.matterworks.core.database.dao.PlotDAO;
import com.matterworks.core.database.dao.PlotResourceDAO;
import com.matterworks.core.domain.machines.PlacedMachine;
import com.matterworks.core.domain.matter.MatterColor;
import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.model.PlotObject;
import com.matterworks.core.ports.IRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MariaDBAdapter implements IRepository {

    private final DatabaseManager dbManager;
    private final PlayerDAO playerDAO;
    private final PlotDAO plotDAO;
    private final PlotResourceDAO resourceDAO;

    public MariaDBAdapter(DatabaseManager dbManager) {
        this.dbManager = dbManager;
        this.playerDAO = new PlayerDAO(dbManager);
        this.plotDAO = new PlotDAO(dbManager);
        this.resourceDAO = new PlotResourceDAO(dbManager);
    }

    // --- NUOVO: Conta oggetti nell'inventario ---
    @Override
    public int getInventoryItemCount(UUID ownerId, String itemId) {
        String sql = "SELECT quantity FROM player_inventory WHERE player_uuid = ? AND item_id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setBytes(1, UuidUtils.asBytes(ownerId));
            stmt.setString(2, itemId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getInt("quantity");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    // ... (Il resto dei metodi rimane invariato: clearPlotData, getPlotId, saveResource, etc.) ...
    // Assicurati di mantenere tutto il codice precedente!

    @Override public void clearPlotData(UUID ownerId) {
        Long plotId = plotDAO.findPlotIdByOwner(ownerId);
        if (plotId == null) return;
        try (Connection conn = dbManager.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM plot_machines WHERE plot_id = ?")) {
                stmt.setLong(1, plotId); stmt.executeUpdate();
            }
            try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM plot_resources WHERE plot_id = ?")) {
                stmt.setLong(1, plotId); stmt.executeUpdate();
            }
            System.out.println("🔥 WIPE COMPLETO per Plot ID " + plotId);
        } catch (SQLException e) { e.printStackTrace(); }
    }

    public Long getPlotId(UUID ownerId) { return plotDAO.findPlotIdByOwner(ownerId); }
    public void saveResource(Long plotId, int x, int z, MatterColor type) { resourceDAO.addResource(plotId, x, z, type); }
    public Map<GridPosition, MatterColor> loadResources(Long plotId) { return resourceDAO.loadResources(plotId); }
    @Override public PlayerProfile loadPlayerProfile(UUID uuid) { return playerDAO.load(uuid); }
    @Override public void savePlayerProfile(PlayerProfile profile) { playerDAO.save(profile); }
    @Override public List<PlotObject> loadPlotMachines(UUID ownerId) { return plotDAO.loadMachines(ownerId); }
    @Override public Long createMachine(UUID ownerId, PlacedMachine machine) {
        String jsonMeta = machine.serialize().toString();
        return plotDAO.insertMachine(ownerId, machine.getTypeId(), machine.getPos().x(), machine.getPos().y(), machine.getPos().z(), jsonMeta);
    }
    @Override public void deleteMachine(Long dbId) { plotDAO.removeMachine(dbId); }
    @Override public void updateMachinesMetadata(List<PlacedMachine> machines) {
        if (machines == null || machines.isEmpty()) return;
        String sql = "UPDATE plot_machines SET metadata = ? WHERE id = ?";
        try (Connection conn = dbManager.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (PlacedMachine pm : machines) {
                if (pm.getDbId() == null) continue;
                stmt.setString(1, pm.serialize().toString());
                stmt.setLong(2, pm.getDbId());
                stmt.addBatch();
            }
            stmt.executeBatch();
        } catch (SQLException e) { e.printStackTrace(); }
    }
}