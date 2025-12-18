package com.matterworks.core.database.dao;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.managers.TechManager.TechNode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class TechDefinitionDAO {

    private final DatabaseManager db;
    private final Gson gson = new Gson();

    public TechDefinitionDAO(DatabaseManager db) {
        this.db = db;
    }

    public List<TechNode> loadAllNodes() {
        List<TechNode> nodes = new ArrayList<>();
        String sql = "SELECT node_id, name_display, cost_money, parent_node_ids, unlock_machine_ids FROM tech_definitions";

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                String id = rs.getString("node_id");
                String name = rs.getString("name_display");
                double cost = rs.getDouble("cost_money");

                // Parsing JSON Dependencies
                String parentJson = rs.getString("parent_node_ids");
                List<String> parents = new ArrayList<>();
                if (parentJson != null && !parentJson.equals("null")) {
                    try {
                        parents = gson.fromJson(parentJson, new TypeToken<List<String>>(){}.getType());
                    } catch (Exception ignored) {}
                }
                // Fallback per compatibilità con il record Java precedente che accettava un solo parent
                String primaryParent = (parents != null && !parents.isEmpty()) ? parents.get(0) : null;

                // Parsing JSON Unlocks
                String unlocksJson = rs.getString("unlock_machine_ids");
                List<String> unlocks = new ArrayList<>();
                if (unlocksJson != null && !unlocksJson.equals("null")) {
                    try {
                        unlocks = gson.fromJson(unlocksJson, new TypeToken<List<String>>(){}.getType());
                    } catch (Exception ignored) {}
                }
                // Fallback per il record Java precedente
                String primaryUnlock = (unlocks != null && !unlocks.isEmpty()) ? unlocks.get(0) : "none";

                nodes.add(new TechNode(id, name, cost, primaryParent, primaryUnlock));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return nodes;
    }
}