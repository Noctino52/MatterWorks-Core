package com.matterworks.core.infrastructure;

import com.matterworks.core.common.GridPosition;
import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.database.dao.*;
import com.matterworks.core.domain.machines.PlacedMachine;
import com.matterworks.core.domain.matter.MatterColor;
import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.model.PlotObject;
import com.matterworks.core.ports.IRepository;
import com.matterworks.core.database.UuidUtils;

import java.math.BigDecimal;
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
    private final InventoryDAO inventoryDAO;
    private final TechDefinitionDAO techDefinitionDAO;
    private final TransactionDAO transactionDAO;

    public MariaDBAdapter(DatabaseManager dbManager) {
        this.dbManager = dbManager;
        this.playerDAO = new PlayerDAO(dbManager);
        this.plotDAO = new PlotDAO(dbManager);
        this.resourceDAO = new PlotResourceDAO(dbManager);
        this.inventoryDAO = new InventoryDAO(dbManager);
        this.techDefinitionDAO = new TechDefinitionDAO(dbManager);
        this.transactionDAO = new TransactionDAO(dbManager);
    }

    // --- FIX: Metodo aggiunto per risolvere l'errore in GridManager ---
    public Long createPlot(UUID ownerId, int x, int z, int worldId) {
        return plotDAO.createPlot(ownerId, x, z, worldId);
    }
    // ------------------------------------------------------------------

    @Override
    public void logTransaction(PlayerProfile player, String actionType, String currency, double amount, String itemId) {
        transactionDAO.logTransaction(player, actionType, currency, BigDecimal.valueOf(amount), itemId);
    }

    @Override
    public ServerConfig loadServerConfig() {
        String sql = "SELECT player_start_money, vein_raw, vein_red, vein_blue, vein_yellow, sos_threshold FROM server_gamestate WHERE id = 1";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return new ServerConfig(
                        rs.getDouble("player_start_money"),
                        rs.getInt("vein_raw"),
                        rs.getInt("vein_red"),
                        rs.getInt("vein_blue"),
                        rs.getInt("vein_yellow"),
                        rs.getDouble("sos_threshold")
                );
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return new ServerConfig(1000.0, 3, 1, 1, 0, 500.0);
    }

    @Override public PlayerProfile loadPlayerProfile(UUID uuid) { return playerDAO.load(uuid); }
    @Override public void savePlayerProfile(PlayerProfile profile) { playerDAO.save(profile); }
    @Override public List<PlayerProfile> getAllPlayers() { return playerDAO.loadAll(); }

    @Override
    public void deletePlayerFull(UUID uuid) {
        if (uuid == null) return;
        Long plotId = plotDAO.findPlotIdByOwner(uuid);
        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (plotId != null) {
                    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM plot_machines WHERE plot_id = ?")) { ps.setLong(1, plotId); ps.executeUpdate(); }
                    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM plot_resources WHERE plot_id = ?")) { ps.setLong(1, plotId); ps.executeUpdate(); }
                    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM plots WHERE id = ?")) { ps.setLong(1, plotId); ps.executeUpdate(); }
                }
                byte[] uuidBytes = UuidUtils.asBytes(uuid);
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM player_inventory WHERE player_uuid = ?")) { ps.setBytes(1, uuidBytes); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM verification_codes WHERE player_uuid = ?")) { ps.setBytes(1, uuidBytes); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM transactions WHERE player_uuid = ?")) { ps.setBytes(1, uuidBytes); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM players WHERE uuid = ?")) { ps.setBytes(1, uuidBytes); ps.executeUpdate(); }
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                ex.printStackTrace();
            } finally { conn.setAutoCommit(true); }
        } catch (SQLException e) { e.printStackTrace(); }
    }

    @Override public int getInventoryItemCount(UUID ownerId, String itemId) { return inventoryDAO.getItemCount(ownerId, itemId); }
    @Override public void modifyInventoryItem(UUID ownerId, String itemId, int delta) { inventoryDAO.modifyItemCount(ownerId, itemId, delta); }
    @Override public List<PlotObject> loadPlotMachines(UUID ownerId) { return plotDAO.loadMachines(ownerId); }

    @Override
    public Long createMachine(UUID ownerId, PlacedMachine machine) {
        String jsonMeta = machine.serialize().toString();
        return plotDAO.insertMachine(ownerId, machine.getTypeId(), machine.getPos().x(), machine.getPos().y(), machine.getPos().z(), jsonMeta);
    }

    @Override public void deleteMachine(Long dbId) { plotDAO.removeMachine(dbId); }

    @Override
    public void updateMachinesMetadata(List<PlacedMachine> machines) {
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

    @Override
    public void clearPlotData(UUID ownerId) {
        Long plotId = plotDAO.findPlotIdByOwner(ownerId);
        if (plotId == null) return;
        try (Connection conn = dbManager.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM plot_machines WHERE plot_id = ?")) { stmt.setLong(1, plotId); stmt.executeUpdate(); }
            try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM plot_resources WHERE plot_id = ?")) { stmt.setLong(1, plotId); stmt.executeUpdate(); }
        } catch (SQLException e) { e.printStackTrace(); }
    }

    @Override public Long getPlotId(UUID ownerId) { return plotDAO.findPlotIdByOwner(ownerId); }
    @Override public void saveResource(Long plotId, int x, int z, MatterColor type) { resourceDAO.addResource(plotId, x, z, type); }
    @Override public Map<GridPosition, MatterColor> loadResources(Long plotId) { return resourceDAO.loadResources(plotId); }

    public TechDefinitionDAO getTechDefinitionDAO() { return techDefinitionDAO; }
    public DatabaseManager getDbManager() { return dbManager; }
}