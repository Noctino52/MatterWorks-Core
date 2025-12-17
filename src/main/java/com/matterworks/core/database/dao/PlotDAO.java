package com.matterworks.core.database.dao;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.database.UuidUtils;
import com.matterworks.core.model.PlotObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PlotDAO {

    private final DatabaseManager dbManager;

    public PlotDAO(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    public Integer findPlotIdByOwner(UUID ownerId) {
        String sql = "SELECT id FROM plots WHERE owner_id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setBytes(1, UuidUtils.asBytes(ownerId));
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getInt("id");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    public void createPlot(UUID ownerId, int x, int z, int worldId) {
        String sql = "INSERT INTO plots (owner_id, x, z, world_id, allocation_index, world_x, world_z, expansion_tier, is_active) VALUES (?, ?, ?, ?, 0, 0, 0, 0, 1)";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setBytes(1, UuidUtils.asBytes(ownerId));
            stmt.setInt(2, x);
            stmt.setInt(3, z);
            stmt.setInt(4, worldId);
            stmt.executeUpdate();
            System.out.println("✅ Plot creato per " + ownerId);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // --- NUOVO METODO PER INSERIRE MACCHINE ---
    public Long insertMachine(UUID ownerId, String typeId, int x, int y, int z, String metadataJson) {
        Integer plotId = findPlotIdByOwner(ownerId);
        if (plotId == null) {
            System.err.println("❌ Nessun plot trovato per salvare la macchina!");
            return null;
        }

        // Usa 'metadata' (senza underscore) come da tuo DB attuale
        String sql = "INSERT INTO plot_machines (plot_id, type_id, x, y, z, metadata) VALUES (?, ?, ?, ?, ?, ?)";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS)) {

            stmt.setInt(1, plotId);
            stmt.setString(2, typeId);
            stmt.setInt(3, x);
            stmt.setInt(4, y);
            stmt.setInt(5, z);
            stmt.setString(6, metadataJson);

            int affected = stmt.executeUpdate();
            if (affected > 0) {
                try (ResultSet rs = stmt.getGeneratedKeys()) {
                    if (rs.next()) return rs.getLong(1);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    public List<PlotObject> loadMachines(UUID ownerId) {
        List<PlotObject> machines = new ArrayList<>();
        String sql = "SELECT pm.id, pm.plot_id, pm.type_id, pm.x, pm.y, pm.z, pm.metadata FROM plot_machines pm JOIN plots p ON pm.plot_id = p.id WHERE p.owner_id = ?";

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
        String sql = "DELETE FROM plot_machines WHERE id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, dbId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}