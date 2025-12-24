package com.matterworks.core.database.dao;

import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.domain.machines.registry.MachineStats;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public class MachineDefinitionDAO {

    private final DatabaseManager db;

    // Query JOIN aggiornata con le nuove colonne
    private static final String SELECT_JOINED_STATS = """
        SELECT 
            i.id, 
            i.category,
            i.base_price, 
            i.tier,
            i.model_id,
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

                // Lettura nuovi campi
                String category = rs.getString("category");
                double price = rs.getDouble("base_price");
                int tier = rs.getInt("tier");
                String modelId = rs.getString("model_id");

                Vector3Int dim = new Vector3Int(
                        rs.getInt("width"),
                        rs.getInt("height"),
                        rs.getInt("depth")
                );

                statsMap.put(id, new MachineStats(id, dim, price, tier, modelId, category));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return statsMap;
    }
}