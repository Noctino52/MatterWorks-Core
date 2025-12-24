package com.matterworks.core.database.dao;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.database.UuidUtils;
import com.matterworks.core.model.PlotObject;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PlotDAO {

    private final DatabaseManager dbManager;

    public PlotDAO(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    public Long findPlotIdByOwner(UUID ownerId) {
        String sql = "SELECT id FROM plots WHERE owner_id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setBytes(1, UuidUtils.asBytes(ownerId));
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getLong("id");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    // (compat) non obbligo item_placed nella INSERT: userà DEFAULT 0
    public Long createPlot(UUID ownerId, int x, int z, int worldId) {
        String sql = "INSERT INTO plots (owner_id, x, z, world_id, allocation_index, world_x, world_z, expansion_tier, is_active) " +
                "VALUES (?, ?, ?, ?, 0, 0, 0, 0, 1)";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            stmt.setBytes(1, UuidUtils.asBytes(ownerId));
            stmt.setInt(2, x);
            stmt.setInt(3, z);
            stmt.setInt(4, worldId);

            int affectedRows = stmt.executeUpdate();
            if (affectedRows > 0) {
                try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
                    if (generatedKeys.next()) {
                        Long id = generatedKeys.getLong(1);
                        System.out.println("✅ Plot creato con ID: " + id + " per " + ownerId);
                        return id;
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    public Long insertMachine(UUID ownerId, String typeId, int x, int y, int z, String metadataJson) {
        Long plotId = findPlotIdByOwner(ownerId);
        if (plotId == null) {
            System.err.println("❌ Nessun plot trovato per salvare la macchina!");
            return null;
        }

        String insertSql = "INSERT INTO plot_machines (plot_id, type_id, x, y, z, metadata) VALUES (?, ?, ?, ?, ?, ?)";
        String incSql = "UPDATE plots SET item_placed = item_placed + 1 WHERE id = ?";

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);

            try (PreparedStatement stmt = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                stmt.setLong(1, plotId);
                stmt.setString(2, typeId);
                stmt.setInt(3, x);
                stmt.setInt(4, y);
                stmt.setInt(5, z);
                stmt.setString(6, metadataJson);

                int affected = stmt.executeUpdate();
                if (affected <= 0) {
                    conn.rollback();
                    return null;
                }

                Long machineId = null;
                try (ResultSet rs = stmt.getGeneratedKeys()) {
                    if (rs.next()) machineId = rs.getLong(1);
                }

                // increment item_placed
                try (PreparedStatement inc = conn.prepareStatement(incSql)) {
                    inc.setLong(1, plotId);
                    inc.executeUpdate();
                }

                conn.commit();
                return machineId;
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
        }
    }

    public List<PlotObject> loadMachines(UUID ownerId) {
        List<PlotObject> machines = new ArrayList<>();
        String sql = "SELECT pm.id, pm.plot_id, pm.type_id, pm.x, pm.y, pm.z, pm.metadata " +
                "FROM plot_machines pm JOIN plots p ON pm.plot_id = p.id WHERE p.owner_id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setBytes(1, UuidUtils.asBytes(ownerId));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String metaStr = rs.getString("metadata");
                    JsonObject metaJson = new JsonObject();
                    if (metaStr != null && !metaStr.isBlank()) {
                        try { metaJson = JsonParser.parseString(metaStr).getAsJsonObject(); }
                        catch (Exception ignored) {}
                    }
                    machines.add(new PlotObject(
                            rs.getLong("id"), rs.getLong("plot_id"),
                            rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                            rs.getString("type_id"), metaJson
                    ));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return machines;
    }

    public void removeMachine(Long dbId) {
        if (dbId == null) return;

        String selSql = "SELECT plot_id FROM plot_machines WHERE id = ?";
        String delSql = "DELETE FROM plot_machines WHERE id = ?";
        String decSql = "UPDATE plots SET item_placed = GREATEST(0, item_placed - 1) WHERE id = ?";

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);

            try {
                Long plotId = null;

                try (PreparedStatement sel = conn.prepareStatement(selSql)) {
                    sel.setLong(1, dbId);
                    try (ResultSet rs = sel.executeQuery()) {
                        if (rs.next()) plotId = rs.getLong("plot_id");
                    }
                }

                try (PreparedStatement del = conn.prepareStatement(delSql)) {
                    del.setLong(1, dbId);
                    del.executeUpdate();
                }

                if (plotId != null) {
                    try (PreparedStatement dec = conn.prepareStatement(decSql)) {
                        dec.setLong(1, plotId);
                        dec.executeUpdate();
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

    public int getItemPlaced(UUID ownerId) {
        Long plotId = findPlotIdByOwner(ownerId);
        if (plotId == null) return 0;

        String sql = "SELECT item_placed FROM plots WHERE id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, plotId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt("item_placed");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    public com.matterworks.core.model.PlotUnlockState loadPlotUnlockState(UUID ownerId) {
        String sql = "SELECT unlocked_extra_x, unlocked_extra_y FROM plots WHERE owner_id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setBytes(1, UuidUtils.asBytes(ownerId));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new com.matterworks.core.model.PlotUnlockState(
                            rs.getInt("unlocked_extra_x"),
                            rs.getInt("unlocked_extra_y")
                    );
                }
            }
        } catch (SQLException e) {
            // backward compat: se la colonna non esiste ancora
            String msg = (e.getMessage() != null) ? e.getMessage().toLowerCase() : "";
            if (!msg.contains("unknown column")) e.printStackTrace();
        }
        return com.matterworks.core.model.PlotUnlockState.zero();
    }

    public boolean updatePlotUnlockState(UUID ownerId, com.matterworks.core.model.PlotUnlockState state) {
        String sql = "UPDATE plots SET unlocked_extra_x = ?, unlocked_extra_y = ? WHERE owner_id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, Math.max(0, state.extraX()));
            ps.setInt(2, Math.max(0, state.extraY()));
            ps.setBytes(3, UuidUtils.asBytes(ownerId));
            return ps.executeUpdate() > 0;

        } catch (SQLException e) {
            String msg = (e.getMessage() != null) ? e.getMessage().toLowerCase() : "";
            if (!msg.contains("unknown column")) e.printStackTrace();
            return false;
        }
    }

}
