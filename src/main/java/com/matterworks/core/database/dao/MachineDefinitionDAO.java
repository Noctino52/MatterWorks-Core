package com.matterworks.core.database.dao;

import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.domain.machines.registry.MachineStats;

import java.sql.*;
import java.util.HashMap;
import java.util.Map;

public class MachineDefinitionDAO {

    private final DatabaseManager db;

    public MachineDefinitionDAO(DatabaseManager db) {
        this.db = db;
    }

    public Map<String, MachineStats> loadAllDefinitions() {
        Map<String, MachineStats> statsMap = new HashMap<>();

        try (Connection conn = db.getConnection()) {

            boolean hasPrestigeMult = columnExists(conn, "item_definitions", "prestigecostmultiplier");

            String sql = """
                SELECT
                    i.id,
                    i.category,
                    i.base_price,
                    %s AS prestige_cost_mult,
                    i.tier,
                    i.model_id,
                    m.width,
                    m.height,
                    m.depth
                FROM item_definitions i
                JOIN machine_definitions m ON i.id = m.type_id
            """.formatted(hasPrestigeMult ? "i.prestigecostmultiplier" : "0.0");

            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    String id = rs.getString("id");
                    String category = rs.getString("category");
                    double price = rs.getDouble("base_price");
                    double prestigeCostMult = rs.getDouble("prestige_cost_mult");
                    int tier = rs.getInt("tier");
                    String modelId = rs.getString("model_id");

                    Vector3Int dim = new Vector3Int(
                            rs.getInt("width"),
                            rs.getInt("height"),
                            rs.getInt("depth")
                    );

                    statsMap.put(id, new MachineStats(id, dim, price, prestigeCostMult, tier, modelId, category));
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }

        return statsMap;
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
}
