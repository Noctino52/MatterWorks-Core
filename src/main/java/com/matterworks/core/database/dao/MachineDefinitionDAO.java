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

    public MachineDefinitionDAO(DatabaseManager db) {
        this.db = db;
    }

    public Map<String, MachineStats> loadAllDefinitions() {
        Map<String, MachineStats> statsMap = new HashMap<>();

        try (Connection conn = db.getConnection()) {

            boolean hasPrestigeMult = columnExists(conn, "item_definitions", "prestigecostmultiplier");
            boolean hasSpeed = columnExists(conn, "machine_definitions", "speed");
            boolean hasShopOrder = columnExists(conn, "item_definitions", "shop_order");

            // New nice names:
            boolean hasPenaltyEveryNew = columnExists(conn, "item_definitions", "price_penalty_every");
            boolean hasPenaltyAddNew = columnExists(conn, "item_definitions", "price_penalty_add");

            // Old names (compat):
            boolean hasPenaltyEveryOld = columnExists(conn, "item_definitions", "amount_of_item_required");
            boolean hasPenaltyAddOld = columnExists(conn, "item_definitions", "money_to_add_per_penality");

            String penaltyEveryExpr = "0";
            if (hasPenaltyEveryNew) penaltyEveryExpr = "i.price_penalty_every";
            else if (hasPenaltyEveryOld) penaltyEveryExpr = "i.amount_of_item_required";

            String penaltyAddExpr = "0.0";
            if (hasPenaltyAddNew) penaltyAddExpr = "i.price_penalty_add";
            else if (hasPenaltyAddOld) penaltyAddExpr = "i.money_to_add_per_penality";

            String sql = """
                SELECT
                    i.id,
                    i.category,
                    i.base_price,
                    %s AS prestige_cost_mult,
                    i.tier,
                    i.model_id,
                    %s AS shop_order,
                    %s AS price_penalty_every,
                    %s AS price_penalty_add,
                    m.width,
                    m.height,
                    m.depth,
                    %s AS speed
                FROM item_definitions i
                JOIN machine_definitions m ON i.id = m.type_id
            """.formatted(
                    hasPrestigeMult ? "i.prestigecostmultiplier" : "0.0",
                    hasShopOrder ? "i.shop_order" : "0",
                    penaltyEveryExpr,
                    penaltyAddExpr,
                    hasSpeed ? "m.speed" : "1.0"
            );

            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    String id = rs.getString("id");
                    String category = rs.getString("category");

                    double price = rs.getDouble("base_price");
                    if (Double.isNaN(price) || Double.isInfinite(price) || price < 0.0) price = 0.0;

                    double prestigeCostMult = rs.getDouble("prestige_cost_mult");
                    if (Double.isNaN(prestigeCostMult) || Double.isInfinite(prestigeCostMult) || prestigeCostMult < 0.0) {
                        prestigeCostMult = 0.0;
                    }

                    int tier = rs.getInt("tier");
                    if (tier <= 0) tier = 1;

                    String modelId = rs.getString("model_id");

                    int shopOrder = 0;
                    try {
                        shopOrder = rs.getInt("shop_order");
                        if (rs.wasNull()) shopOrder = 0;
                    } catch (SQLException ignored) {
                        shopOrder = 0;
                    }

                    int pricePenaltyEvery = 0;
                    try {
                        pricePenaltyEvery = rs.getInt("price_penalty_every");
                        if (rs.wasNull() || pricePenaltyEvery < 0) pricePenaltyEvery = 0;
                    } catch (SQLException ignored) {
                        pricePenaltyEvery = 0;
                    }

                    double pricePenaltyAdd = 0.0;
                    try {
                        pricePenaltyAdd = rs.getDouble("price_penalty_add");
                        if (Double.isNaN(pricePenaltyAdd) || Double.isInfinite(pricePenaltyAdd) || pricePenaltyAdd < 0.0) {
                            pricePenaltyAdd = 0.0;
                        }
                    } catch (SQLException ignored) {
                        pricePenaltyAdd = 0.0;
                    }

                    Vector3Int dim = new Vector3Int(
                            rs.getInt("width"),
                            rs.getInt("height"),
                            rs.getInt("depth")
                    );

                    double speed = rs.getDouble("speed");
                    if (Double.isNaN(speed) || Double.isInfinite(speed) || speed <= 0.0) speed = 1.0;

                    statsMap.put(
                            id,
                            new MachineStats(
                                    id,
                                    dim,
                                    price,
                                    prestigeCostMult,
                                    tier,
                                    modelId,
                                    category,
                                    pricePenaltyEvery,
                                    pricePenaltyAdd,
                                    speed,
                                    shopOrder
                            )
                    );
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
