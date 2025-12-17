package com.matterworks.core.database.dao;

import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.database.UuidUtils;
import com.matterworks.core.domain.player.PlayerProfile;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PlayerDAO {

    private final DatabaseManager db;

    // UPDATE: Aggiunto 'rank' alla query
    private static final String UPSERT_SQL = """
        INSERT INTO players (uuid, username, money, rank) 
        VALUES (?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE 
            username = VALUES(username),
            money = VALUES(money),
            rank = VALUES(rank),
            last_login = CURRENT_TIMESTAMP
    """;

    private static final String SELECT_SQL = "SELECT * FROM players WHERE uuid = ?";

    public PlayerDAO(DatabaseManager db) {
        this.db = db;
    }

    public void save(PlayerProfile p) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(UPSERT_SQL)) {

            ps.setBytes(1, UuidUtils.asBytes(p.getPlayerId()));
            ps.setString(2, p.getUsername());
            ps.setDouble(3, p.getMoney());
            ps.setString(4, p.getRank().name()); // Salva Enum come Stringa

            ps.executeUpdate();

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public PlayerProfile load(UUID uuid) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_SQL)) {

            ps.setBytes(1, UuidUtils.asBytes(uuid));

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    PlayerProfile p = new PlayerProfile(uuid);
                    p.setUsername(rs.getString("username"));
                    p.setMoney(rs.getDouble("money"));

                    // Carica Rank (con fallback se null)
                    String rankStr = rs.getString("rank");
                    if (rankStr != null) {
                        try {
                            p.setRank(PlayerProfile.PlayerRank.valueOf(rankStr));
                        } catch (IllegalArgumentException e) {
                            p.setRank(PlayerProfile.PlayerRank.PLAYER);
                        }
                    }
                    return p;
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null; // Non trovato
    }

    public List<PlayerProfile> loadAll() {
        List<PlayerProfile> players = new ArrayList<>();
        String sql = "SELECT * FROM players";
        try (java.sql.Connection conn = db.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(sql);
             java.sql.ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                PlayerProfile p = new PlayerProfile(UuidUtils.asUuid(rs.getBytes("uuid")));
                p.setUsername(rs.getString("username"));
                p.setMoney(rs.getDouble("money"));
                String rankStr = rs.getString("rank");
                if (rankStr != null) p.setRank(PlayerProfile.PlayerRank.valueOf(rankStr));
                players.add(p);
            }
        } catch (java.sql.SQLException e) { e.printStackTrace(); }
        return players;
    }
}