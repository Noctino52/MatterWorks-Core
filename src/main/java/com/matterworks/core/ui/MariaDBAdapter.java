package com.matterworks.core.ui;

import com.matterworks.core.common.GridPosition;
import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.database.UuidUtils;
import com.matterworks.core.database.dao.InventoryDAO;
import com.matterworks.core.database.dao.PlayerDAO;
import com.matterworks.core.database.dao.PlotDAO;
import com.matterworks.core.database.dao.PlotResourceDAO;
import com.matterworks.core.database.dao.TechDefinitionDAO;
import com.matterworks.core.database.dao.TransactionDAO;
import com.matterworks.core.database.dao.VoidShopDAO;
import com.matterworks.core.domain.machines.base.PlacedMachine;
import com.matterworks.core.domain.matter.MatterColor;
import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.domain.shop.VoidShopItem;
import com.matterworks.core.model.PlotObject;
import com.matterworks.core.model.PlotUnlockState;
import com.matterworks.core.ports.IRepository;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

public class MariaDBAdapter implements IRepository {

    private final DatabaseManager dbManager;
    private final PlayerDAO playerDAO;
    private final PlotDAO plotDAO;
    private final PlotResourceDAO resourceDAO;
    private final InventoryDAO inventoryDAO;
    private final TechDefinitionDAO techDefinitionDAO;
    private final TransactionDAO transactionDAO;

    // ✅ VOID SHOP
    private final VoidShopDAO voidShopDAO;

    public MariaDBAdapter(DatabaseManager dbManager) {
        this.dbManager = dbManager;
        this.playerDAO = new PlayerDAO(dbManager);
        this.plotDAO = new PlotDAO(dbManager);
        this.resourceDAO = new PlotResourceDAO(dbManager);
        this.inventoryDAO = new InventoryDAO(dbManager);
        this.techDefinitionDAO = new TechDefinitionDAO(dbManager);
        this.transactionDAO = new TransactionDAO(dbManager);

        this.voidShopDAO = new VoidShopDAO(dbManager);
    }

    // --- Extra (non in IRepository) ---
    public Long createPlot(UUID ownerId, int x, int z, int worldId) {
        return plotDAO.createPlot(ownerId, x, z, worldId);
    }

    // ==========================================================
    // VOID SHOP
    // ==========================================================
    @Override
    public List<VoidShopItem> loadVoidShopCatalog() {
        try {
            return voidShopDAO.loadAll();
        } catch (Throwable t) {
            return List.of();
        }
    }

    @Override
    public VoidShopItem loadVoidShopItem(String itemId) {
        if (itemId == null || itemId.isBlank()) return null;
        try {
            return voidShopDAO.loadById(itemId);
        } catch (Throwable t) {
            return null;
        }
    }

    // ==========================================================
    // CAP PLOT ITEMS
    // ==========================================================
    @Override
    public int getDefaultItemPlacedOnPlotCap() {
        String sql = "SELECT default_item_placed_on_plot FROM server_gamestate WHERE id = 1";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            if (rs.next()) {
                int v = rs.getInt("default_item_placed_on_plot");
                return Math.max(1, v);
            }
        } catch (SQLException e) {
            if (!isUnknownColumn(e)) e.printStackTrace();
        }
        return 1000;
    }

    @Override
    public int getPlotItemsPlaced(UUID ownerId) {
        if (ownerId == null) return 0;

        Long plotId = plotDAO.findPlotIdByOwner(ownerId);
        if (plotId == null) return 0;

        // prefer: plots.item_placed
        String sql = "SELECT item_placed FROM plots WHERE id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, plotId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Math.max(0, rs.getInt("item_placed"));
            }
        } catch (SQLException e) {
            if (!isUnknownColumn(e)) {
                e.printStackTrace();
            } else {
                // fallback: COUNT(*) se colonna non c'è (DB vecchio)
                return countPlotMachines(plotId);
            }
        }
        return 0;
    }

    private int countPlotMachines(Long plotId) {
        if (plotId == null) return 0;
        String sql = "SELECT COUNT(*) AS c FROM plot_machines WHERE plot_id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, plotId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Math.max(0, rs.getInt("c"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    // ==========================================================
    // TRANSACTIONS
    // ==========================================================
    @Override
    public void logTransaction(PlayerProfile player, String actionType, String currency, double amount, String itemId) {
        transactionDAO.logTransaction(player, actionType, currency, BigDecimal.valueOf(amount), itemId);
    }

    // ==========================================================
    // CONFIG
    // ==========================================================
    @Override
    public ServerConfig loadServerConfig() {

        // defaults “safe”
        double playerStartMoney = 1000.0;
        int veinRaw = 3, veinRed = 1, veinBlue = 1, veinYellow = 0;
        double sosThreshold = 500.0;
        int maxInventoryMachine = 64;

        int plotStartingX = 25, plotStartingY = 25;
        int plotMaxX = 50, plotMaxY = 50;
        int plotIncreaseX = 2, plotIncreaseY = 2;

        int prestigeVoidCoinsAdd = 0;
        int prestigePlotBonus = 0;
        double prestigeSellK = 0.0;

        try (Connection conn = dbManager.getConnection()) {

            boolean hasMaxInv = columnExists(conn, "server_gamestate", "max_inventory_machine");

            // plot columns: legacy + snake_case
            String colPlotStartX = firstExistingColumn(conn, "server_gamestate",
                    "plot_start_x", "Plot_Starting_X", "plot_starting_x");
            String colPlotStartY = firstExistingColumn(conn, "server_gamestate",
                    "plot_start_y", "Plot_Starting_Y", "plot_starting_y");
            String colPlotMaxX = firstExistingColumn(conn, "server_gamestate",
                    "plot_max_x", "Plot_Max_X", "plot_max_x");
            String colPlotMaxY = firstExistingColumn(conn, "server_gamestate",
                    "plot_max_y", "Plot_Max_Y", "plot_max_y");
            String colPlotIncX = firstExistingColumn(conn, "server_gamestate",
                    "plot_increase_x", "Plot_IncreaseX", "Plot_Increase_X", "plot_increasex");
            String colPlotIncY = firstExistingColumn(conn, "server_gamestate",
                    "plot_increase_y", "Plot_IncreaseY", "Plot_Increase_Y", "plot_increasey");

            // prestige columns
            String colPrestigeVoid = firstExistingColumn(conn, "server_gamestate", "prestige_void_coins_add");
            String colPrestigePlotBonus = firstExistingColumn(conn, "server_gamestate", "prestige_plotbonus", "prestige_plot_bonus");
            String colPrestigeSellK = firstExistingColumn(conn, "server_gamestate", "prestige_sell_k");

            StringBuilder sql = new StringBuilder();
            sql.append("SELECT ")
                    .append("player_start_money, vein_raw, vein_red, vein_blue, vein_yellow, sos_threshold");

            if (hasMaxInv) sql.append(", max_inventory_machine");

            if (colPlotStartX != null) sql.append(", ").append(colPlotStartX).append(" AS plot_start_x");
            if (colPlotStartY != null) sql.append(", ").append(colPlotStartY).append(" AS plot_start_y");
            if (colPlotMaxX != null) sql.append(", ").append(colPlotMaxX).append(" AS plot_max_x");
            if (colPlotMaxY != null) sql.append(", ").append(colPlotMaxY).append(" AS plot_max_y");
            if (colPlotIncX != null) sql.append(", ").append(colPlotIncX).append(" AS plot_inc_x");
            if (colPlotIncY != null) sql.append(", ").append(colPlotIncY).append(" AS plot_inc_y");

            if (colPrestigeVoid != null) sql.append(", ").append(colPrestigeVoid).append(" AS prestige_void_coins_add");
            if (colPrestigePlotBonus != null) sql.append(", ").append(colPrestigePlotBonus).append(" AS prestige_plotbonus");
            if (colPrestigeSellK != null) sql.append(", ").append(colPrestigeSellK).append(" AS prestige_sell_k");

            sql.append(" FROM server_gamestate WHERE id = 1");

            try (PreparedStatement ps = conn.prepareStatement(sql.toString());
                 ResultSet rs = ps.executeQuery()) {

                if (rs.next()) {
                    playerStartMoney = rs.getDouble("player_start_money");
                    veinRaw = rs.getInt("vein_raw");
                    veinRed = rs.getInt("vein_red");
                    veinBlue = rs.getInt("vein_blue");
                    veinYellow = rs.getInt("vein_yellow");
                    sosThreshold = rs.getDouble("sos_threshold");

                    if (hasMaxInv) maxInventoryMachine = Math.max(1, rs.getInt("max_inventory_machine"));

                    if (colPlotStartX != null) plotStartingX = Math.max(1, rs.getInt("plot_start_x"));
                    if (colPlotStartY != null) plotStartingY = Math.max(1, rs.getInt("plot_start_y"));
                    if (colPlotMaxX != null) plotMaxX = Math.max(1, rs.getInt("plot_max_x"));
                    if (colPlotMaxY != null) plotMaxY = Math.max(1, rs.getInt("plot_max_y"));
                    if (colPlotIncX != null) plotIncreaseX = Math.max(1, rs.getInt("plot_inc_x"));
                    if (colPlotIncY != null) plotIncreaseY = Math.max(1, rs.getInt("plot_inc_y"));

                    if (colPrestigeVoid != null) prestigeVoidCoinsAdd = Math.max(0, rs.getInt("prestige_void_coins_add"));
                    if (colPrestigePlotBonus != null) prestigePlotBonus = Math.max(0, rs.getInt("prestige_plotbonus"));
                    if (colPrestigeSellK != null) prestigeSellK = Math.max(0.0, rs.getDouble("prestige_sell_k"));
                }
            }

        } catch (SQLException ignored) {
        }

        return new ServerConfig(
                playerStartMoney,
                veinRaw, veinRed, veinBlue, veinYellow,
                sosThreshold,
                maxInventoryMachine,
                plotStartingX, plotStartingY,
                plotMaxX, plotMaxY,
                plotIncreaseX, plotIncreaseY,
                prestigeVoidCoinsAdd,
                prestigePlotBonus,
                prestigeSellK
        );
    }

    @Override
    public int getItemCapIncreaseStep() {
        String sql = "SELECT itemcap_increase_step FROM server_gamestate WHERE id = 1";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            if (rs.next()) return Math.max(0, rs.getInt("itemcap_increase_step"));
        } catch (SQLException e) {
            if (!isUnknownColumn(e)) e.printStackTrace();
        }
        return 0;
    }

    @Override
    public int getMaxItemPlacedOnPlotCap() {
        String sql = "SELECT max_item_placed_on_plot FROM server_gamestate WHERE id = 1";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            if (rs.next()) {
                int v = rs.getInt("max_item_placed_on_plot");
                return (v <= 0) ? Integer.MAX_VALUE : v;
            }
        } catch (SQLException e) {
            if (!isUnknownColumn(e)) e.printStackTrace();
        }
        return Integer.MAX_VALUE;
    }

    @Override
    public void updateMaxItemPlacedOnPlotCap(int newCap) {
        String sql = "UPDATE server_gamestate SET max_item_placed_on_plot = ? WHERE id = 1";
        int cap = (newCap <= 0) ? 0 : newCap; // 0 = unlimited (come già gestisci in lettura)
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, cap);
            ps.executeUpdate();
        } catch (SQLException e) {
            if (!isUnknownColumn(e)) e.printStackTrace();
        }
    }

    // ==========================================================
    // PLOT UNLOCK
    // ==========================================================
    @Override
    public PlotUnlockState loadPlotUnlockState(UUID ownerId) {
        return plotDAO.loadPlotUnlockState(ownerId);
    }

    @Override
    public boolean updatePlotUnlockState(UUID ownerId, PlotUnlockState state) {
        return plotDAO.updatePlotUnlockState(ownerId, state);
    }

    // ==========================================================
    // MinutesToInactive (server_gamestate)
    // ==========================================================
    public int loadMinutesToInactive() {
        String sql = "SELECT MinutesToInactive FROM server_gamestate WHERE id = 1";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                int v = rs.getInt("MinutesToInactive");
                return Math.max(1, v);
            }
        } catch (SQLException ignored) {}
        return 5;
    }

    // ==========================================================
    // Player Sessions (player_session)
    // ==========================================================
    @Override
    public void openPlayerSession(UUID playerUuid) {
        if (playerUuid == null) return;

        byte[] uuidBytes = UuidUtils.asBytes(playerUuid);

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE player_session SET logout_at = NOW() WHERE player_uuid = ? AND logout_at IS NULL")) {
                    ps.setBytes(1, uuidBytes);
                    ps.executeUpdate();
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO player_session (player_uuid, login_at, logout_at) VALUES (?, NOW(), NULL)")) {
                    ps.setBytes(1, uuidBytes);
                    ps.executeUpdate();
                }

                safeExecuteIgnoreUnknownColumn(conn,
                        "UPDATE players SET last_login = NOW() WHERE uuid = ?",
                        uuidBytes
                );

                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                ex.printStackTrace();
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void closePlayerSession(UUID playerUuid) {
        if (playerUuid == null) return;

        byte[] uuidBytes = UuidUtils.asBytes(playerUuid);

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                boolean updated = safeUpdateSessionWithSeconds(conn, uuidBytes);
                if (!updated) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE player_session " +
                                    "SET logout_at = NOW() " +
                                    "WHERE player_uuid = ? AND logout_at IS NULL " +
                                    "ORDER BY login_at DESC LIMIT 1")) {
                        ps.setBytes(1, uuidBytes);
                        ps.executeUpdate();
                    }
                }

                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                ex.printStackTrace();
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private boolean safeUpdateSessionWithSeconds(Connection conn, byte[] uuidBytes) {
        String sql =
                "UPDATE player_session " +
                        "SET logout_at = NOW(), session_seconds = TIMESTAMPDIFF(SECOND, login_at, NOW()) " +
                        "WHERE player_uuid = ? AND logout_at IS NULL " +
                        "ORDER BY login_at DESC LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBytes(1, uuidBytes);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (isUnknownColumn(e)) return false;
            throw new RuntimeException(e);
        }
    }

    private void safeExecuteIgnoreUnknownColumn(Connection conn, String sql, byte[] uuidBytes) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBytes(1, uuidBytes);
            ps.executeUpdate();
        } catch (SQLException e) {
            if (!isUnknownColumn(e)) e.printStackTrace();
        }
    }

    private boolean isUnknownColumn(SQLException e) {
        String state = e.getSQLState();
        return "42S22".equals(state) || (e.getMessage() != null && e.getMessage().contains("Unknown column"));
    }

    private boolean columnExists(Connection conn, String table, String column) throws SQLException {
        String sql =
                "SELECT 1 FROM information_schema.COLUMNS " +
                        "WHERE table_schema = DATABASE() " +
                        "AND LOWER(table_name) = LOWER(?) " +
                        "AND LOWER(column_name) = LOWER(?) " +
                        "LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private String firstExistingColumn(Connection conn, String table, String... candidates) throws SQLException {
        for (String c : candidates) {
            if (c != null && !c.isBlank() && columnExists(conn, table, c)) return c;
        }
        return null;
    }

    // ==========================================================
    // IRepository standard
    // ==========================================================
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

                    // best-effort reset contatore
                    try (PreparedStatement ps = conn.prepareStatement("UPDATE plots SET item_placed = 0 WHERE id = ?")) {
                        ps.setLong(1, plotId);
                        ps.executeUpdate();
                    } catch (SQLException e) {
                        if (!isUnknownColumn(e)) throw e;
                    }

                    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM plots WHERE id = ?")) { ps.setLong(1, plotId); ps.executeUpdate(); }
                }

                byte[] uuidBytes = UuidUtils.asBytes(uuid);
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM player_inventory WHERE player_uuid = ?")) { ps.setBytes(1, uuidBytes); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM verification_codes WHERE player_uuid = ?")) { ps.setBytes(1, uuidBytes); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM transactions WHERE player_uuid = ?")) { ps.setBytes(1, uuidBytes); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM player_session WHERE player_uuid = ?")) { ps.setBytes(1, uuidBytes); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM players WHERE uuid = ?")) { ps.setBytes(1, uuidBytes); ps.executeUpdate(); }

                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                ex.printStackTrace();
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override public int getInventoryItemCount(UUID ownerId, String itemId) { return inventoryDAO.getItemCount(ownerId, itemId); }
    @Override public void modifyInventoryItem(UUID ownerId, String itemId, int delta) { inventoryDAO.modifyItemCount(ownerId, itemId, delta); }

    @Override public List<PlotObject> loadPlotMachines(UUID ownerId) { return plotDAO.loadMachines(ownerId); }

    @Override
    public Long createMachine(UUID ownerId, PlacedMachine machine) {
        String jsonMeta = machine.serialize().toString();
        return plotDAO.insertMachine(ownerId, machine.getTypeId(),
                machine.getPos().x(), machine.getPos().y(), machine.getPos().z(),
                jsonMeta);
    }

    @Override public void deleteMachine(Long dbId) { plotDAO.removeMachine(dbId); }

    @Override
    public void updateMachinesMetadata(List<PlacedMachine> machines) {
        if (machines == null || machines.isEmpty()) return;

        Map<UUID, List<PlacedMachine>> byOwner = machines.stream()
                .filter(Objects::nonNull)
                .filter(m -> m.getDbId() != null)
                .collect(Collectors.groupingBy(PlacedMachine::getOwnerId));

        for (Map.Entry<UUID, List<PlacedMachine>> entry : byOwner.entrySet()) {
            UUID ownerId = entry.getKey();
            List<PlacedMachine> list = entry.getValue();
            if (ownerId == null || list.isEmpty()) continue;

            String sql = "UPDATE plot_machines SET metadata = ? WHERE id = ?";

            try (Connection conn = dbManager.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                for (PlacedMachine pm : list) {
                    stmt.setString(1, pm.serialize().toString());
                    stmt.setLong(2, pm.getDbId());
                    stmt.addBatch();
                }
                stmt.executeBatch();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to update machines metadata for ownerId=" + ownerId, e);
            }
        }
    }

    @Override
    public void clearPlotData(UUID ownerId) {
        if (ownerId == null) return;

        Long plotId = plotDAO.findPlotIdByOwner(ownerId);
        if (plotId == null) return;

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM plot_machines WHERE plot_id = ?")) {
                    stmt.setLong(1, plotId);
                    stmt.executeUpdate();
                }

                // ❌ NON TOCCARE unlocked_extra_x / unlocked_extra_y

                try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM plot_resources WHERE plot_id = ?")) {
                    stmt.setLong(1, plotId);
                    stmt.executeUpdate();
                }

                try (PreparedStatement stmt = conn.prepareStatement("UPDATE plots SET item_placed = 0 WHERE id = ?")) {
                    stmt.setLong(1, plotId);
                    stmt.executeUpdate();
                } catch (SQLException e) {
                    if (!isUnknownColumn(e)) throw e;
                }

                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                ex.printStackTrace();
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override public Long getPlotId(UUID ownerId) { return plotDAO.findPlotIdByOwner(ownerId); }

    @Override public void saveResource(Long plotId, int x, int z, MatterColor type) { resourceDAO.addResource(plotId, x, z, type); }
    @Override public Map<GridPosition, MatterColor> loadResources(Long plotId) { return resourceDAO.loadResources(plotId); }

    public TechDefinitionDAO getTechDefinitionDAO() { return techDefinitionDAO; }
    public DatabaseManager getDbManager() { return dbManager; }

    public boolean purchaseVoidShopItemAtomic(UUID playerId, String itemId, int unitPrice, int amount, boolean isAdmin) {
        if (playerId == null || itemId == null || itemId.isBlank() || amount <= 0) return false;

        long totalL = (long) unitPrice * (long) amount;
        if (totalL > Integer.MAX_VALUE) totalL = Integer.MAX_VALUE;
        int total = (int) totalL;

        Connection conn = null;
        try {
            conn = dbManager.getConnection();
            conn.setAutoCommit(false);

            // 1) scala void coins SOLO se non admin
            if (!isAdmin) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE players SET void_coins = void_coins - ? WHERE uuid = ? AND void_coins >= ?"
                )) {
                    ps.setInt(1, total);
                    ps.setBytes(2, UuidUtils.asBytes(playerId));
                    ps.setInt(3, total);

                    int rows = ps.executeUpdate();
                    if (rows != 1) {
                        conn.rollback();
                        return false; // non abbastanza void coins (o player non trovato)
                    }
                }
            }

            // 2) assegna item in inventario (upsert)
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO player_inventory (player_uuid, item_id, quantity) VALUES (?, ?, ?) " +
                            "ON DUPLICATE KEY UPDATE quantity = quantity + VALUES(quantity)"
            )) {
                ps.setBytes(1, UuidUtils.asBytes(playerId));
                ps.setString(2, itemId);
                ps.setInt(3, amount);
                ps.executeUpdate();
            }

            conn.commit();
            return true;

        } catch (SQLException e) {
            try { if (conn != null) conn.rollback(); } catch (SQLException ignored) {}
            e.printStackTrace();
            return false;

        } finally {
            if (conn != null) {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
                try { conn.close(); } catch (SQLException ignored) {}
            }
        }
    }

}
