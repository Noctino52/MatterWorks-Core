package com.matterworks.core.database.dao;

import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.database.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public class MachineDefinitionDAO {

    private final DatabaseManager db;
    private static final String SELECT_ALL = "SELECT type_id, width, height, depth FROM machine_definitions";

    public MachineDefinitionDAO(DatabaseManager db) {
        this.db = db;
    }

    public Map<String, Vector3Int> loadAllDefinitions() {
        Map<String, Vector3Int> defs = new HashMap<>();

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_ALL);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                String id = rs.getString("type_id");
                Vector3Int dim = new Vector3Int(
                        rs.getInt("width"),
                        rs.getInt("height"),
                        rs.getInt("depth")
                );
                defs.put(id, dim);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return defs;
    }
}