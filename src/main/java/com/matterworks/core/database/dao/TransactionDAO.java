package com.matterworks.core.database.dao;

import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.database.UuidUtils;
import com.matterworks.core.domain.player.PlayerProfile;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class TransactionDAO {

    private final DatabaseManager db;

    private static final String INSERT_SQL = """
        INSERT INTO transactions (
            player_uuid, username, money_balance, void_coins_balance, prestige_level, 
            action_type, currency, amount, item_id, occurred_at
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
    """;

    public TransactionDAO(DatabaseManager db) {
        this.db = db;
    }

    public void logTransaction(PlayerProfile p, String type, String currency, BigDecimal amount, String itemId) {
        new Thread(() -> {
            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {

                ps.setBytes(1, UuidUtils.asBytes(p.getPlayerId()));
                ps.setString(2, p.getUsername());
                ps.setBigDecimal(3, BigDecimal.valueOf(p.getMoney()));
                ps.setInt(4, p.getVoidCoins());
                ps.setInt(5, p.getPrestigeLevel());
                ps.setString(6, type);
                ps.setString(7, currency);
                ps.setBigDecimal(8, amount);
                ps.setString(9, itemId);

                ps.executeUpdate();

            } catch (SQLException e) {
                System.err.println("FAILED TO LOG TRANSACTION: " + e.getMessage());
            }
        }).start();
    }
}