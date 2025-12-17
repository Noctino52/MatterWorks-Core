package com.matterworks.core.database.dao;

import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.database.UuidUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public class InventoryDAO {

    private final DatabaseManager db;

    public InventoryDAO(DatabaseManager db) {
        this.db = db;
    }

    public int getItemCount(UUID playerUuid, String itemId) {
        String sql = "SELECT quantity FROM player_inventory WHERE player_uuid = ? AND item_id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBytes(1, UuidUtils.asBytes(playerUuid));
            ps.setString(2, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt("quantity");
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return 0;
    }

    public void modifyItemCount(UUID playerUuid, String itemId, int delta) {
        // UPSERT: Se esiste aggiorna, se no inserisce.
        // GREATEST(0, ...) assicura che non andiamo mai in negativo nel DB
        String sql = """
            INSERT INTO player_inventory (player_uuid, item_id, quantity) 
            VALUES (?, ?, ?) 
            ON DUPLICATE KEY UPDATE quantity = GREATEST(0, quantity + VALUES(quantity))
        """;

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBytes(1, UuidUtils.asBytes(playerUuid));
            ps.setString(2, itemId);
            // Se delta è negativo (es. -1), l'inventario scende. Se positivo, sale.
            ps.setInt(3, Math.max(0, delta)); // Nota: per l'insert iniziale assumiamo positivo o 0

            // Correzione per la logica SQL pura:
            // La query sopra funziona bene per INSERT. Ma per l'UPDATE vogliamo sommare il delta.
            // Sostituiamo con una query più esplicita per gestire il delta correttamente in Java

            doModify(conn, playerUuid, itemId, delta);

        } catch (SQLException e) { e.printStackTrace(); }
    }

    private void doModify(Connection conn, UUID uuid, String itemId, int delta) throws SQLException {
        // 1. Controlla esistenza
        String checkSql = "SELECT quantity FROM player_inventory WHERE player_uuid = ? AND item_id = ?";
        int current = 0;
        boolean exists = false;

        try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
            ps.setBytes(1, UuidUtils.asBytes(uuid));
            ps.setString(2, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    current = rs.getInt("quantity");
                    exists = true;
                }
            }
        }

        int newVal = Math.max(0, current + delta);

        if (exists) {
            String update = "UPDATE player_inventory SET quantity = ? WHERE player_uuid = ? AND item_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(update)) {
                ps.setInt(1, newVal);
                ps.setBytes(2, UuidUtils.asBytes(uuid));
                ps.setString(3, itemId);
                ps.executeUpdate();
            }
        } else if (newVal > 0) {
            String insert = "INSERT INTO player_inventory (player_uuid, item_id, quantity) VALUES (?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(insert)) {
                ps.setBytes(1, UuidUtils.asBytes(uuid));
                ps.setString(2, itemId);
                ps.setInt(3, newVal);
                ps.executeUpdate();
            }
        }
    }
}