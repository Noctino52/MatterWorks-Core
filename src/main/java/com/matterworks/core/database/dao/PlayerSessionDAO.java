package com.matterworks.core.database.dao;

import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.database.UuidUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

public class PlayerSessionDAO {

    private final DatabaseManager db;

    public PlayerSessionDAO(DatabaseManager db) {
        this.db = db;
    }

    public void openPlayerSession(UUID playerUuid) {
        if (playerUuid == null) return;

        byte[] uuidBytes = UuidUtils.asBytes(playerUuid);

        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE player_session SET logout_at = NOW() WHERE player_uuid = ? AND logout_at IS NULL")) {
                    ps.setBytes(1, uuidBytes);
                    ps.executeUpdate();
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO player_session (player_uuid, login_at, logout_at) VALUES (?, NOW(), NULL)")) {
                    ps.setBytes(1, uuidBytes);
                    ps.executeUpdate();
                }

                // compat: last_login might not exist
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE players SET last_login = NOW() WHERE uuid = ?")) {
                    ps.setBytes(1, uuidBytes);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    if (!SqlCompat.isUnknownColumn(e)) e.printStackTrace();
                }

                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                ex.printStackTrace();
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void closePlayerSession(UUID playerUuid) {
        if (playerUuid == null) return;

        byte[] uuidBytes = UuidUtils.asBytes(playerUuid);

        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                boolean updated = safeUpdateSessionWithSeconds(conn, uuidBytes);
                if (!updated) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE player_session " +
                                    "SET logout_at = NOW() " +
                                    "WHERE player_uuid = ? AND logout_at IS NULL " +
                                    "ORDER BY login_at DESC LIMIT 1")) {
                        ps.setBytes(1, uuidBytes);
                        ps.executeUpdate();
                    }
                }
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                ex.printStackTrace();
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private boolean safeUpdateSessionWithSeconds(Connection conn, byte[] uuidBytes) {
        String sql =
                "UPDATE player_session " +
                        "SET logout_at = NOW(), session_seconds = TIMESTAMPDIFF(SECOND, login_at, NOW()) " +
                        "WHERE player_uuid = ? AND logout_at IS NULL " +
                        "ORDER BY login_at DESC LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBytes(1, uuidBytes);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (SqlCompat.isUnknownColumn(e)) return false;
            throw new RuntimeException(e);
        }
    }
}
