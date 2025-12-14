package com.matterworks.core.database.dao;

import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.database.UuidUtils; // Importante

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public class PlotDAO {

    private final DatabaseManager db;

    // owner_id è BINARY(16)
    private static final String SELECT_BY_OWNER = "SELECT id FROM plots WHERE owner_id = ?";
    private static final String INSERT_PLOT = "INSERT INTO plots (owner_id, allocation_index, world_x, world_z) VALUES (?, ?, ?, ?)";

    public PlotDAO(DatabaseManager db) {
        this.db = db;
    }

    public Long findPlotIdByOwner(UUID ownerId) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BY_OWNER)) {

            // FIX: Conversione UUID -> Bytes
            ps.setBytes(1, UuidUtils.asBytes(ownerId));

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("id");
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    public void createPlot(UUID ownerId, int allocationIndex, int worldX, int worldZ) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(INSERT_PLOT)) {

            // FIX: Conversione UUID -> Bytes
            ps.setBytes(1, UuidUtils.asBytes(ownerId));
            ps.setInt(2, allocationIndex);
            ps.setInt(3, worldX);
            ps.setInt(4, worldZ);

            ps.executeUpdate();

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}