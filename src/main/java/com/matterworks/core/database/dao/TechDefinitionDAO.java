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

/**
 * Loads Tech Tree nodes from DB.
 *
 * Backward compatible:
 * - Works with legacy schema (only unlock_machine_ids + parent_node_ids + prestige_cost_mult optional)
 * - Supports upgrade nodes (tier 2/3) via optional columns:
 *   - upgrade_machine_ids (JSON list)
 *   - upgrade_to_tier (INT)
 *   - speed_multiplier (DOUBLE)
 *   - nexus_sell_multiplier (DOUBLE)
 *   - enables_prestige (TINYINT/BOOLEAN)
 */
public class TechDefinitionDAO {

    private final DatabaseManager db;
    private final Gson gson = new Gson();

    public TechDefinitionDAO(DatabaseManager db) {
        this.db = db;
    }

    public List<TechNode> loadAllNodes() {
        List<TechNode> out = new ArrayList<>();

        try (Connection conn = db.getConnection()) {

            boolean hasPrestigeMult = SqlCompat.columnExists(conn, "tech_definitions", "prestige_cost_mult");

            boolean hasUpgradeMachineIds = SqlCompat.columnExists(conn, "tech_definitions", "upgrade_machine_ids");
            boolean hasUpgradeToTier = SqlCompat.columnExists(conn, "tech_definitions", "upgrade_to_tier");
            boolean hasSpeedMultiplier = SqlCompat.columnExists(conn, "tech_definitions", "speed_multiplier");
            boolean hasNexusSellMultiplier = SqlCompat.columnExists(conn, "tech_definitions", "nexus_sell_multiplier");
            boolean hasEnablesPrestige = SqlCompat.columnExists(conn, "tech_definitions", "enables_prestige");

            StringBuilder sb = new StringBuilder();
            sb.append("SELECT node_id, name_display, cost_money, parent_node_ids, unlock_machine_ids");

            if (hasPrestigeMult) sb.append(", prestige_cost_mult");
            if (hasUpgradeMachineIds) sb.append(", upgrade_machine_ids");
            if (hasUpgradeToTier) sb.append(", upgrade_to_tier");
            if (hasSpeedMultiplier) sb.append(", speed_multiplier");
            if (hasNexusSellMultiplier) sb.append(", nexus_sell_multiplier");
            if (hasEnablesPrestige) sb.append(", enables_prestige");

            sb.append(" FROM tech_definitions");

            String sql = sb.toString();

            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    String id = rs.getString("node_id");
                    if (id == null || id.isBlank()) continue;

                    String name = rs.getString("name_display");
                    double baseCost = rs.getDouble("cost_money");

                    List<String> parents = parseJsonList(rs.getString("parent_node_ids"));
                    List<String> unlocks = parseJsonList(rs.getString("unlock_machine_ids"));

                    double prestigeCostMult = 0.0;
                    if (hasPrestigeMult) {
                        prestigeCostMult = rs.getDouble("prestige_cost_mult");
                        if (rs.wasNull()) prestigeCostMult = 0.0;
                    }

                    List<String> upgradeMachines = new ArrayList<>();
                    if (hasUpgradeMachineIds) {
                        upgradeMachines = parseJsonList(rs.getString("upgrade_machine_ids"));
                    }

                    int upgradeToTier = 0;
                    if (hasUpgradeToTier) {
                        upgradeToTier = rs.getInt("upgrade_to_tier");
                        if (rs.wasNull()) upgradeToTier = 0;
                    }

                    double speedMultiplier = 1.0;
                    if (hasSpeedMultiplier) {
                        speedMultiplier = rs.getDouble("speed_multiplier");
                        if (rs.wasNull()) speedMultiplier = 1.0;
                    }

                    double nexusSellMultiplier = 1.0;
                    if (hasNexusSellMultiplier) {
                        nexusSellMultiplier = rs.getDouble("nexus_sell_multiplier");
                        if (rs.wasNull()) nexusSellMultiplier = 1.0;
                    }

                    boolean enablesPrestige = false;
                    if (hasEnablesPrestige) {
                        int v = rs.getInt("enables_prestige");
                        if (!rs.wasNull()) enablesPrestige = (v != 0);
                    }

                    // Hard rule: node_id == "prestige" always enables prestige (even if column missing).
                    if ("prestige".equalsIgnoreCase(id)) enablesPrestige = true;

                    out.add(new TechNode(
                            id,
                            name,
                            baseCost,
                            prestigeCostMult,
                            parents,
                            unlocks,
                            upgradeMachines,
                            upgradeToTier,
                            speedMultiplier,
                            nexusSellMultiplier,
                            enablesPrestige
                    ));
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }

        return out;
    }

    private List<String> parseJsonList(String json) {
        if (json == null || json.isBlank() || "null".equalsIgnoreCase(json)) {
            return new ArrayList<>();
        }
        try {
            return gson.fromJson(json, new TypeToken<List<String>>() {}.getType());
        } catch (Exception e) {
            System.err.println("⚠️ JSON Parse Error in TechDefinitionDAO: " + json);
            return new ArrayList<>();
        }
    }
}
