package com.matterworks.core.infrastructure;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.database.UuidUtils;
import com.matterworks.core.database.dao.*;
import com.matterworks.core.domain.machines.PlacedMachine;
import com.matterworks.core.domain.matter.MatterColor;
import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.model.PlotObject;
import com.matterworks.core.ports.IRepository;

import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public class MariaDBAdapter implements IRepository {

    private final DatabaseManager dbManager;
    private final PlayerDAO playerDAO;
    private final PlotDAO plotDAO;
    private final PlotResourceDAO resourceDAO;
    private final InventoryDAO inventoryDAO;
    private final TechDefinitionDAO techDefinitionDAO;
    private final TransactionDAO transactionDAO;

    private final ConcurrentHashMap<UUID, ReentrantLock> ownerLocks = new ConcurrentHashMap<>();

    public MariaDBAdapter(DatabaseManager dbManager) {
        this.dbManager = dbManager;
        this.playerDAO = new PlayerDAO(dbManager);
        this.plotDAO = new PlotDAO(dbManager);
        this.resourceDAO = new PlotResourceDAO(dbManager);
        this.inventoryDAO = new InventoryDAO(dbManager);
        this.techDefinitionDAO = new TechDefinitionDAO(dbManager);
        this.transactionDAO = new TransactionDAO(dbManager);
    }

    private ReentrantLock lockFor(UUID ownerId) {
        return ownerLocks.computeIfAbsent(ownerId, _k -> new ReentrantLock());
    }

    public Long createPlot(UUID ownerId, int x, int z, int worldId) {
        return plotDAO.createPlot(ownerId, x, z, worldId);
    }

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
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return new ServerConfig(1000.0, 3, 1, 1, 0, 500.0);
    }

    @Override public PlayerProfile loadPlayerProfile(UUID uuid) { return playerDAO.load(uuid); }
    @Override public void savePlayerProfile(PlayerProfile profile) { playerDAO.save(profile); }
    @Override public List<PlayerProfile> getAllPlayers() { return playerDAO.loadAll(); }

    @Override
    public void deletePlayerFull(UUID uuid) {
        if (uuid == null) return;

        ReentrantLock lock = lockFor(uuid);
        lock.lock();
        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Long plotId = lockPlotIdByOwner(conn, uuid);
                if (plotId != null) {
                    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM plot_machines WHERE plot_id = ?")) {
                        ps.setLong(1, plotId);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM plot_resources WHERE plot_id = ?")) {
                        ps.setLong(1, plotId);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM plots WHERE id = ?")) {
                        ps.setLong(1, plotId);
                        ps.executeUpdate();
                    }
                }

                byte[] uuidBytes = UuidUtils.asBytes(uuid);

                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM player_inventory WHERE player_uuid = ?")) {
                    ps.setBytes(1, uuidBytes);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM verification_codes WHERE player_uuid = ?")) {
                    ps.setBytes(1, uuidBytes);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM transactions WHERE player_uuid = ?")) {
                    ps.setBytes(1, uuidBytes);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM players WHERE uuid = ?")) {
                    ps.setBytes(1, uuidBytes);
                    ps.executeUpdate();
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
        } finally {
            lock.unlock();
        }
    }

    @Override public int getInventoryItemCount(UUID ownerId, String itemId) { return inventoryDAO.getItemCount(ownerId, itemId); }
    @Override public void modifyInventoryItem(UUID ownerId, String itemId, int delta) { inventoryDAO.modifyItemCount(ownerId, itemId, delta); }

    @Override
    public List<PlotObject> loadPlotMachines(UUID ownerId) {
        if (ownerId == null) return List.of();

        String sql = """
                SELECT pm.id, pm.plot_id, pm.type_id, pm.x, pm.y, pm.z, pm.metadata
                FROM plot_machines pm
                JOIN plots p ON pm.plot_id = p.id
                WHERE p.owner_id = ?
                ORDER BY pm.plot_id, pm.x, pm.y, pm.z, pm.id
                """;

        Map<String, PlotObject> latestByAnchor = new LinkedHashMap<>();
        List<Long> toDelete = new ArrayList<>();

        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setBytes(1, UuidUtils.asBytes(ownerId));

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    long plotId = rs.getLong("plot_id");
                    int x = rs.getInt("x");
                    int y = rs.getInt("y");
                    int z = rs.getInt("z");
                    String typeId = rs.getString("type_id");
                    String metaStr = rs.getString("metadata");

                    JsonObject metaJson = new JsonObject();
                    if (metaStr != null && !metaStr.isBlank()) {
                        try {
                            metaJson = JsonParser.parseString(metaStr).getAsJsonObject();
                        } catch (Exception ignored) {
                            metaJson = new JsonObject();
                        }
                    }

                    String key = plotId + ":" + x + ":" + y + ":" + z;
                    PlotObject existing = latestByAnchor.get(key);

                    if (existing == null) {
                        latestByAnchor.put(key, new PlotObject(id, plotId, x, y, z, typeId, metaJson));
                    } else {
                        long existingId = existing.getId() != null ? existing.getId() : -1L;
                        if (id > existingId) {
                            toDelete.add(existingId);
                            latestByAnchor.put(key, new PlotObject(id, plotId, x, y, z, typeId, metaJson));
                        } else {
                            toDelete.add(id);
                        }
                    }
                }
            }

            if (!toDelete.isEmpty()) {
                try (PreparedStatement del = conn.prepareStatement("DELETE FROM plot_machines WHERE id = ?")) {
                    for (Long id : toDelete) {
                        if (id == null || id <= 0) continue;
                        del.setLong(1, id);
                        del.addBatch();
                    }
                    del.executeBatch();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }

        return new ArrayList<>(latestByAnchor.values());
    }

    @Override
    public Long createMachine(UUID ownerId, PlacedMachine machine) {
        if (ownerId == null || machine == null || machine.getPos() == null) return null;

        ReentrantLock lock = lockFor(ownerId);
        lock.lock();
        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Long plotId = lockPlotIdByOwner(conn, ownerId);
                if (plotId == null) {
                    Long created = plotDAO.createPlot(ownerId, 1, 0, 0);
                    plotId = (created != null) ? lockPlotIdByOwner(conn, ownerId) : null;
                }
                if (plotId == null) {
                    conn.rollback();
                    System.err.println("❌ Nessun plot trovato/creato per salvare la macchina (owner=" + ownerId + ")");
                    return null;
                }

                GridPosition p = machine.getPos();

                Long existingId = findExistingMachineIdByAnchor(conn, plotId, p.x(), p.y(), p.z());
                String meta = safeJson(machine.serialize());

                Long finalId;
                if (existingId != null) {
                    updateMachineRow(conn, existingId, machine.getTypeId(), meta);
                    finalId = existingId;
                } else {
                    finalId = insertMachineRow(conn, plotId, machine.getTypeId(), p.x(), p.y(), p.z(), meta);
                }

                conn.commit();

                if (finalId != null) {
                    machine.setDbId(finalId);
                }

                return finalId;
            } catch (SQLException ex) {
                conn.rollback();
                ex.printStackTrace();
                return null;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return null;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void deleteMachine(Long dbId) {
        if (dbId == null) return;

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Long plotId = null;
                try (PreparedStatement ps = conn.prepareStatement("SELECT plot_id FROM plot_machines WHERE id = ? FOR UPDATE")) {
                    ps.setLong(1, dbId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) plotId = rs.getLong("plot_id");
                    }
                }

                if (plotId != null) {
                    try (PreparedStatement lockPlot = conn.prepareStatement("SELECT id FROM plots WHERE id = ? FOR UPDATE")) {
                        lockPlot.setLong(1, plotId);
                        lockPlot.executeQuery();
                    }
                }

                try (PreparedStatement del = conn.prepareStatement("DELETE FROM plot_machines WHERE id = ?")) {
                    del.setLong(1, dbId);
                    del.executeUpdate();
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

    @Override
    public void updateMachinesMetadata(List<PlacedMachine> machines) {
        if (machines == null || machines.isEmpty()) return;

        Map<UUID, List<PlacedMachine>> byOwner = new HashMap<>();
        for (PlacedMachine m : machines) {
            if (m == null) continue;
            UUID ownerId = m.getOwnerId();
            if (ownerId == null) continue;
            if (m.getDbId() == null) continue;
            byOwner.computeIfAbsent(ownerId, _k -> new ArrayList<>()).add(m);
        }

        for (Map.Entry<UUID, List<PlacedMachine>> e : byOwner.entrySet()) {
            UUID ownerId = e.getKey();
            List<PlacedMachine> list = e.getValue();
            if (list == null || list.isEmpty()) continue;

            ReentrantLock lock = lockFor(ownerId);
            lock.lock();
            try (Connection conn = dbManager.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    Long plotId = lockPlotIdByOwner(conn, ownerId);
                    if (plotId == null) {
                        conn.rollback();
                        continue;
                    }

                    dedupeAnchorsForPlot(conn, plotId);

                    String sql = "UPDATE plot_machines SET metadata = ?, type_id = ? WHERE id = ?";
                    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                        for (PlacedMachine pm : list) {
                            Long id = pm.getDbId();
                            if (id == null) continue;
                            stmt.setString(1, safeJson(pm.serialize()));
                            stmt.setString(2, pm.getTypeId());
                            stmt.setLong(3, id);
                            stmt.addBatch();
                        }
                        stmt.executeBatch();
                    }

                    conn.commit();
                } catch (SQLException ex) {
                    conn.rollback();
                    throw new RuntimeException("Failed to update machines metadata for ownerId=" + ownerId, ex);
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException ex) {
                throw new RuntimeException("Failed to update machines metadata for ownerId=" + ownerId, ex);
            } finally {
                lock.unlock();
            }
        }
    }

    @Override
    public void clearPlotData(UUID ownerId) {
        if (ownerId == null) return;

        ReentrantLock lock = lockFor(ownerId);
        lock.lock();
        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Long plotId = lockPlotIdByOwner(conn, ownerId);
                if (plotId == null) {
                    conn.rollback();
                    return;
                }
                try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM plot_machines WHERE plot_id = ?")) {
                    stmt.setLong(1, plotId);
                    stmt.executeUpdate();
                }
                try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM plot_resources WHERE plot_id = ?")) {
                    stmt.setLong(1, plotId);
                    stmt.executeUpdate();
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
        } finally {
            lock.unlock();
        }
    }

    @Override public Long getPlotId(UUID ownerId) { return plotDAO.findPlotIdByOwner(ownerId); }
    @Override public void saveResource(Long plotId, int x, int z, MatterColor type) { resourceDAO.addResource(plotId, x, z, type); }
    @Override public Map<GridPosition, MatterColor> loadResources(Long plotId) { return resourceDAO.loadResources(plotId); }

    private Long lockPlotIdByOwner(Connection conn, UUID ownerId) throws SQLException {
        String sql = "SELECT id FROM plots WHERE owner_id = ? FOR UPDATE";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBytes(1, UuidUtils.asBytes(ownerId));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong("id");
            }
        }
        return null;
    }

    private Long findExistingMachineIdByAnchor(Connection conn, long plotId, int x, int y, int z) throws SQLException {
        String sql = "SELECT id FROM plot_machines WHERE plot_id = ? AND x = ? AND y = ? AND z = ? ORDER BY id DESC LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, plotId);
            ps.setInt(2, x);
            ps.setInt(3, y);
            ps.setInt(4, z);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong("id");
            }
        }
        return null;
    }

    private void updateMachineRow(Connection conn, long id, String typeId, String meta) throws SQLException {
        String sql = "UPDATE plot_machines SET type_id = ?, metadata = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, typeId);
            ps.setString(2, meta);
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    private Long insertMachineRow(Connection conn, long plotId, String typeId, int x, int y, int z, String meta) throws SQLException {
        String sql = "INSERT INTO plot_machines (plot_id, type_id, x, y, z, metadata) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, plotId);
            ps.setString(2, typeId);
            ps.setInt(3, x);
            ps.setInt(4, y);
            ps.setInt(5, z);
            ps.setString(6, meta);
            int affected = ps.executeUpdate();
            if (affected > 0) {
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) return rs.getLong(1);
                }
            }
        }
        return null;
    }

    private void dedupeAnchorsForPlot(Connection conn, long plotId) throws SQLException {
        String sql = """
                DELETE pm FROM plot_machines pm
                JOIN (
                    SELECT plot_id, x, y, z, MAX(id) AS keep_id
                    FROM plot_machines
                    WHERE plot_id = ?
                    GROUP BY plot_id, x, y, z
                    HAVING COUNT(*) > 1
                ) d
                ON pm.plot_id = d.plot_id
                AND pm.x = d.x AND pm.y = d.y AND pm.z = d.z
                AND pm.id <> d.keep_id
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, plotId);
            ps.executeUpdate();
        }
    }

    private String safeJson(JsonObject obj) {
        try {
            return (obj != null) ? obj.toString() : "{}";
        } catch (Exception e) {
            return "{}";
        }
    }

    public TechDefinitionDAO getTechDefinitionDAO() { return techDefinitionDAO; }
    public DatabaseManager getDbManager() { return dbManager; }
}
