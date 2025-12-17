package com.matterworks.core.database.dao;

import com.matterworks.core.common.GridPosition;
import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.domain.matter.MatterColor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public class PlotResourceDAO {

    private final DatabaseManager db;

    public PlotResourceDAO(DatabaseManager db) {
        this.db = db;
    }

    // UPDATE: Long plotId
    public void addResource(Long plotId, int x, int z, MatterColor type) {
        String sql = "INSERT INTO plot_resources (plot_id, x, z, resource_type) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE resource_type = VALUES(resource_type)";

        try (Connection conn = db.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, plotId); // setLong
            stmt.setInt(2, x);
            stmt.setInt(3, z);
            stmt.setString(4, type.name());
            stmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // UPDATE: Long plotId
    public Map<GridPosition, MatterColor> loadResources(Long plotId) {
        Map<GridPosition, MatterColor> resources = new HashMap<>();
        String sql = "SELECT x, z, resource_type FROM plot_resources WHERE plot_id = ?";

        try (Connection conn = db.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, plotId); // setLong

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    int x = rs.getInt("x");
                    int z = rs.getInt("z");
                    String typeStr = rs.getString("resource_type");

                    try {
                        MatterColor color = MatterColor.valueOf(typeStr);
                        resources.put(new GridPosition(x, 0, z), color);
                    } catch (IllegalArgumentException e) {
                        System.err.println("⚠️ Risorsa ignota nel DB: " + typeStr);
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return resources;
    }
}