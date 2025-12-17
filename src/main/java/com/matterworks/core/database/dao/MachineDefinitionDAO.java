package com.matterworks.core.database.dao;

import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.domain.machines.MachineStats;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public class MachineDefinitionDAO {

    private final DatabaseManager db;

    // JOIN per prendere Prezzo (item_definitions) e Dimensioni (machine_definitions)
    private static final String SELECT_JOINED_STATS = """
        SELECT 
            i.id, 
            i.base_price, 
            i.category,
            m.width, 
            m.height, 
            m.depth
        FROM item_definitions i
        JOIN machine_definitions m ON i.id = m.type_id
    """;

    public MachineDefinitionDAO(DatabaseManager db) {
        this.db = db;
    }

    public Map<String, MachineStats> loadAllDefinitions() {
        Map<String, MachineStats> statsMap = new HashMap<>();

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_JOINED_STATS);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                String id = rs.getString("id");
                double price = rs.getDouble("base_price");

                Vector3Int dim = new Vector3Int(
                        rs.getInt("width"),
                        rs.getInt("height"),
                        rs.getInt("depth")
                );

                statsMap.put(id, new MachineStats(dim, price, id));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return statsMap;
    }
}