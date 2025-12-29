package com.matterworks.core.database.dao;

import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.database.UuidUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

public class VoidShopPurchaseDAO {

    private final DatabaseManager db;

    public VoidShopPurchaseDAO(DatabaseManager db) {
        this.db = db;
    }

    public boolean purchaseVoidShopItemAtomic(UUID playerId, String itemId, int unitPrice, int amount, boolean isAdmin) {
        if (playerId == null || itemId == null || itemId.isBlank() || amount <= 0) return false;

        long totalL = (long) unitPrice * (long) amount;
        if (totalL > Integer.MAX_VALUE) totalL = Integer.MAX_VALUE;
        int total = (int) totalL;

        Connection conn = null;
        try {
            conn = db.getConnection();
            conn.setAutoCommit(false);

            // 1) scala void coins SOLO se non admin
            if (!isAdmin) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE players SET void_coins = void_coins - ? WHERE uuid = ? AND void_coins >= ?"
                )) {
                    ps.setInt(1, total);
                    ps.setBytes(2, UuidUtils.asBytes(playerId));
                    ps.setInt(3, total);

                    int rows = ps.executeUpdate();
                    if (rows != 1) {
                        conn.rollback();
                        return false;
                    }
                }
            }

            // 2) assegna item in inventario (upsert)
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO player_inventory (player_uuid, item_id, quantity) VALUES (?, ?, ?) " +
                            "ON DUPLICATE KEY UPDATE quantity = quantity + VALUES(quantity)"
            )) {
                ps.setBytes(1, UuidUtils.asBytes(playerId));
                ps.setString(2, itemId);
                ps.setInt(3, amount);
                ps.executeUpdate();
            }

            conn.commit();
            return true;

        } catch (SQLException e) {
            try { if (conn != null) conn.rollback(); } catch (SQLException ignored) {}
            e.printStackTrace();
            return false;

        } finally {
            if (conn != null) {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
                try { conn.close(); } catch (SQLException ignored) {}
            }
        }
    }
}
