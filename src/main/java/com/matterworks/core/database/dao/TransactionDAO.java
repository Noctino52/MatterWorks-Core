package com.matterworks.core.database.dao;

import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.database.UuidUtils;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

public class TransactionDAO {

    private final DatabaseManager db;

    private static final String INSERT_SQL = """
        INSERT INTO transactions (player_uuid, action_type, currency, amount, item_id, occurred_at)
        VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
    """;

    public TransactionDAO(DatabaseManager db) {
        this.db = db;
    }

    /**
     * Logga una transazione economica.
     * @param uuid Chi?
     * @param type Cosa ha fatto? (es. "MARKET_SELL", "TECH_BUY")
     * @param currency Valuta ("MONEY", "VOID_COINS")
     * @param amount Quanto? (Positivo = guadagno, Negativo = spesa)
     * @param itemId Opzionale (null se non rilevante)
     */
    public void logTransaction(UUID uuid, String type, String currency, BigDecimal amount, String itemId) {
        // Eseguiamo in un thread separato per non bloccare il gioco (Fire & Forget)
        // In un server vero useremmo un ExecutorService, qui per semplicità va bene così
        new Thread(() -> {
            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {

                ps.setBytes(1, UuidUtils.asBytes(uuid));
                ps.setString(2, type);
                ps.setString(3, currency);
                ps.setBigDecimal(4, amount);
                ps.setString(5, itemId); // Può essere null

                ps.executeUpdate();

            } catch (SQLException e) {
                System.err.println("FAILED TO LOG TRANSACTION: " + e.getMessage());
                // Non crashare il gioco per un log fallito, ma stampalo
            }
        }).start();
    }
}