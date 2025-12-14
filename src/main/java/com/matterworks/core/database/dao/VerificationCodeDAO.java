package com.matterworks.core.database.dao;

import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.database.UuidUtils;
import com.matterworks.core.domain.player.LinkCode; // Importante: Usa il Dominio

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.UUID;

public class VerificationCodeDAO {

    private final DatabaseManager db;

    private static final String INSERT_CODE = """
        INSERT INTO verification_codes (code, player_uuid, expires_at)
        VALUES (?, ?, ?)
    """;

    public VerificationCodeDAO(DatabaseManager db) {
        this.db = db;
    }

    public void saveCode(UUID playerUuid, LinkCode linkCode) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(INSERT_CODE)) {

            ps.setString(1, linkCode.code());
            ps.setBytes(2, UuidUtils.asBytes(playerUuid));
            // Convertiamo il long (millisecondi) in Timestamp SQL
            ps.setTimestamp(3, new Timestamp(linkCode.expirationTime()));

            ps.executeUpdate();

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}