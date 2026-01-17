package com.matterworks.core.database.dao;

import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.database.UuidUtils;
import com.matterworks.core.domain.player.PlayerProfile;

import java.math.BigDecimal;
import java.sql.*;

public class TransactionDAO {

    private final DatabaseManager db;

    private volatile boolean schemaChecked = false;
    private volatile boolean hasFactionIdColumn = false;
    private volatile boolean hasValueColumn = false;

    public TransactionDAO(DatabaseManager db) {
        this.db = db;
    }

    public void logTransaction(PlayerProfile p, String type, String currency, BigDecimal amount, String itemId) {
        logTransaction(p, type, currency, amount, itemId, null, null);
    }

    public void logTransaction(
            PlayerProfile p,
            String type,
            String currency,
            BigDecimal amount,
            String itemId,
            Integer factionId,
            BigDecimal value
    ) {
        if (p == null) return;

        new Thread(() -> {
            ensureSchemaFlags();

            String sql = buildInsertSql();

            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {

                int i = 1;

                ps.setBytes(i++, UuidUtils.asBytes(p.getPlayerId()));
                ps.setString(i++, p.getUsername());
                ps.setBigDecimal(i++, BigDecimal.valueOf(p.getMoney()));
                ps.setInt(i++, p.getVoidCoins());
                ps.setInt(i++, p.getPrestigeLevel());

                ps.setString(i++, type);
                ps.setString(i++, currency);

                ps.setBigDecimal(i++, amount != null ? amount : BigDecimal.ZERO);
                ps.setString(i++, itemId);

                if (hasFactionIdColumn) {
                    if (factionId == null) ps.setNull(i++, Types.INTEGER);
                    else ps.setInt(i++, factionId);
                }

                if (hasValueColumn) {
                    if (value == null) ps.setNull(i++, Types.DECIMAL);
                    else ps.setBigDecimal(i++, value);
                }

                ps.executeUpdate();

            } catch (SQLException e) {
                System.err.println("FAILED TO LOG TRANSACTION: " + e.getMessage());
            }
        }).start();
    }

    private void ensureSchemaFlags() {
        if (schemaChecked) return;

        synchronized (this) {
            if (schemaChecked) return;

            boolean faction = false;
            boolean value = false;

            try (Connection conn = db.getConnection()) {
                DatabaseMetaData meta = conn.getMetaData();
                faction = hasColumn(meta, "transactions", "faction_id");
                value = hasColumn(meta, "transactions", "value");
            } catch (SQLException ignored) {
                // If we can't check schema, we fall back to minimal insert
            }

            this.hasFactionIdColumn = faction;
            this.hasValueColumn = value;
            this.schemaChecked = true;
        }
    }

    private boolean hasColumn(DatabaseMetaData meta, String table, String column) throws SQLException {
        try (ResultSet rs = meta.getColumns(null, null, table, column)) {
            if (rs.next()) return true;
        }
        // Try uppercase/lowercase variants if the driver is picky
        try (ResultSet rs = meta.getColumns(null, null, table.toUpperCase(), column.toUpperCase())) {
            return rs.next();
        }
    }

    private String buildInsertSql() {
        StringBuilder cols = new StringBuilder();
        StringBuilder vals = new StringBuilder();

        cols.append("player_uuid, username, money_balance, void_coins_balance, prestige_level, ");
        cols.append("action_type, currency, amount, item_id");

        vals.append("?, ?, ?, ?, ?, ?, ?, ?, ?");

        if (hasFactionIdColumn) {
            cols.append(", faction_id");
            vals.append(", ?");
        }

        if (hasValueColumn) {
            cols.append(", value");
            vals.append(", ?");
        }

        cols.append(", occurred_at");
        vals.append(", CURRENT_TIMESTAMP");

        return "INSERT INTO transactions (" + cols + ") VALUES (" + vals + ")";
    }
}
