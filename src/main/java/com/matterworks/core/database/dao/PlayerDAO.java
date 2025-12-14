package com.matterworks.core.database.dao;

import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.database.UuidUtils; // Importante
import com.matterworks.core.domain.player.PlayerProfile;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public class PlayerDAO {

    private final DatabaseManager db;

    // UUID è BINARY(16) nel DB
    private static final String UPSERT_SQL = """
        INSERT INTO players (uuid, username, money) 
        VALUES (?, ?, ?)
        ON DUPLICATE KEY UPDATE 
            username = VALUES(username),
            money = VALUES(money),
            last_login = CURRENT_TIMESTAMP
    """;

    private static final String SELECT_SQL = "SELECT * FROM players WHERE uuid = ?";

    public PlayerDAO(DatabaseManager db) {
        this.db = db;
    }

    public void save(PlayerProfile p) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(UPSERT_SQL)) {

            // FIX: Usiamo setBytes invece di setString
            ps.setBytes(1, UuidUtils.asBytes(p.getPlayerId()));
            ps.setString(2, p.getUsername());
            ps.setDouble(3, p.getMoney());

            ps.executeUpdate();

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public PlayerProfile load(UUID uuid) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_SQL)) {

            // FIX: Usiamo setBytes per la ricerca
            ps.setBytes(1, UuidUtils.asBytes(uuid));

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    // FIX: Ricostruiamo l'UUID dai byte (anche se l'abbiamo già passato, è buona pratica)
                    // In questo caso usiamo l'UUID passato per creare l'oggetto
                    PlayerProfile p = new PlayerProfile(uuid);
                    p.setUsername(rs.getString("username"));
                    p.setMoney(rs.getDouble("money"));
                    return p;
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }
}