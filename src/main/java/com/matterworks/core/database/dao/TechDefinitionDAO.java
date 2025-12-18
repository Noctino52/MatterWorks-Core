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

                // Deserializzazione liste JSON
                List<String> parents = parseJsonList(rs.getString("parent_node_ids"));
                List<String> unlocks = parseJsonList(rs.getString("unlock_machine_ids"));

                nodes.add(new TechNode(id, name, cost, parents, unlocks));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return nodes;
    }

    private List<String> parseJsonList(String json) {
        if (json == null || json.isBlank() || json.equals("null")) {
            return new ArrayList<>();
        }
        try {
            return gson.fromJson(json, new TypeToken<List<String>>(){}.getType());
        } catch (Exception e) {
            System.err.println("⚠️ JSON Parse Error in TechDefinitionDAO: " + json);
            return new ArrayList<>();
        }
    }
}