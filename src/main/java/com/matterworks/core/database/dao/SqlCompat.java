package com.matterworks.core.database.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class SqlCompat {

    private SqlCompat() {}

    public static boolean isUnknownColumn(SQLException e) {
        String state = e.getSQLState();
        return "42S22".equals(state) || (e.getMessage() != null && e.getMessage().contains("Unknown column"));
    }

    public static boolean columnExists(Connection conn, String table, String column) throws SQLException {
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

    public static String firstExistingColumn(Connection conn, String table, String... candidates) throws SQLException {
        for (String c : candidates) {
            if (c != null && !c.isBlank() && columnExists(conn, table, c)) return c;
        }
        return null;
    }
}
