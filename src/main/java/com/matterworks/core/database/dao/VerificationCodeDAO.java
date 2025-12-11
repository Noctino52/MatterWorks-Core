package com.matterworks.core.database.dao;

import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.database.UuidUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

public class VerificationCodeDAO {

    private final DatabaseManager db;

    // Crea un codice che scade tra 5 minuti
    private static final String INSERT_CODE = """
        INSERT INTO verification_codes (code, player_uuid, expires_at)
        VALUES (?, ?, DATE_ADD(NOW(), INTERVAL 5 MINUTE))
    """;

    public VerificationCodeDAO(DatabaseManager db) {
        this.db = db;
    }

    public void saveCode(UUID playerUuid, String code) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(INSERT_CODE)) {

            ps.setString(1, code);
            ps.setBytes(2, UuidUtils.asBytes(playerUuid));

            ps.executeUpdate();

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}