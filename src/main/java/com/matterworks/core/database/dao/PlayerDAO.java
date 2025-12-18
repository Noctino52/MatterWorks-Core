package com.matterworks.core.database.dao;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.database.UuidUtils;
import com.matterworks.core.domain.player.PlayerProfile;
import java.lang.reflect.Type;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class PlayerDAO {

    private final DatabaseManager db;
    private final Gson gson = new Gson();

    private static final String UPSERT_SQL = """
        INSERT INTO players (uuid, username, money, rank, tech_unlocks) 
        VALUES (?, ?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE 
            username = VALUES(username),
            money = VALUES(money),
            rank = VALUES(rank),
            tech_unlocks = VALUES(tech_unlocks),
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
            ps.setString(4, p.getRank().name());

            // Assicuriamoci di salvare sempre un array []
            String json = gson.toJson(p.getUnlockedTechs());
            ps.setString(5, json);

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

                    String rankStr = rs.getString("rank");
                    if (rankStr != null) {
                        try {
                            p.setRank(PlayerProfile.PlayerRank.valueOf(rankStr));
                        } catch (IllegalArgumentException e) {
                            p.setRank(PlayerProfile.PlayerRank.PLAYER);
                        }
                    }

                    // --- FIX: Parsing Sicuro del JSON ---
                    String techJson = rs.getString("tech_unlocks");
                    if (techJson != null && !techJson.isBlank()) {
                        try {
                            // Se techJson è "{}" (oggetto) invece di "[]" (array), questo fallirebbe senza catch
                            Type setType = new TypeToken<HashSet<String>>(){}.getType();
                            Set<String> unlocks = gson.fromJson(techJson, setType);
                            if (unlocks != null) {
                                unlocks.forEach(p::addTech);
                            }
                        } catch (Exception e) {
                            System.err.println("⚠️ Warning: Formato tech_unlocks errato nel DB per " + uuid + ". Uso set vuoto.");
                            // Non facciamo nulla, p ha già un set vuoto di default
                        }
                    }

                    return p;
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    public List<PlayerProfile> loadAll() {
        List<PlayerProfile> players = new ArrayList<>();
        String sql = "SELECT uuid FROM players";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                PlayerProfile p = load(UuidUtils.asUuid(rs.getBytes("uuid")));
                if (p != null) players.add(p);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return players;
    }
}