package com.matterworks.core.database.dao;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.database.UuidUtils;
import com.matterworks.core.domain.player.PlayerProfile;

import java.lang.reflect.Type;
import java.sql.*;
import java.util.*;

public class PlayerDAO {

    private final DatabaseManager db;
    private final Gson gson = new Gson();

    private static final String UPSERT_SQL = """
        INSERT INTO players (uuid, username, money, void_coins, prestige_level, rank, tech_unlocks)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE
            username = VALUES(username),
            money = VALUES(money),
            void_coins = VALUES(void_coins),
            prestige_level = VALUES(prestige_level),
            rank = VALUES(rank),
            tech_unlocks = VALUES(tech_unlocks)
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
            ps.setInt(4, p.getVoidCoins());
            ps.setInt(5, p.getPrestigeLevel());
            ps.setString(6, p.getRank().name());

            String techJson = gson.toJson(p.getUnlockedTechs() != null ? p.getUnlockedTechs() : Set.of());
            ps.setString(7, techJson);

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
                if (!rs.next()) return null;

                PlayerProfile p = new PlayerProfile(uuid);
                p.setUsername(rs.getString("username"));
                p.setMoney(rs.getDouble("money"));
                p.setVoidCoins(rs.getInt("void_coins"));
                p.setPrestigeLevel(rs.getInt("prestige_level"));

                String rankStr = rs.getString("rank");
                if (rankStr != null) {
                    try { p.setRank(PlayerProfile.PlayerRank.valueOf(rankStr)); }
                    catch (Exception ignored) { p.setRank(PlayerProfile.PlayerRank.PLAYER); }
                } else {
                    p.setRank(PlayerProfile.PlayerRank.PLAYER);
                }

                String techJson = rs.getString("tech_unlocks");
                if (techJson != null && !techJson.isBlank()) {
                    try {
                        Type setType = new TypeToken<HashSet<String>>(){}.getType();
                        Set<String> unlocks = gson.fromJson(techJson, setType);
                        if (unlocks != null) unlocks.forEach(p::addTech);
                    } catch (Exception ex) {
                        System.err.println("⚠️ Warning: tech_unlocks invalid for " + uuid + " -> using empty set.");
                    }
                }

                return p;
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
                UUID id = UuidUtils.asUuid(rs.getBytes("uuid"));
                PlayerProfile p = load(id);
                if (p != null) players.add(p);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return players;
    }

    // ==========================================================
    // SESSIONS (player_sessions)
    // ==========================================================

    public void openSession(UUID uuid) {
        if (uuid == null) return;

        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement closeOld = conn.prepareStatement(
                        "UPDATE player_sessions SET logout_at = CURRENT_TIMESTAMP WHERE player_uuid = ? AND logout_at IS NULL")) {
                    closeOld.setBytes(1, UuidUtils.asBytes(uuid));
                    closeOld.executeUpdate();
                }

                try (PreparedStatement ins = conn.prepareStatement(
                        "INSERT INTO player_sessions (player_uuid, login_at, logout_at) VALUES (?, CURRENT_TIMESTAMP, NULL)")) {
                    ins.setBytes(1, UuidUtils.asBytes(uuid));
                    ins.executeUpdate();
                }

                try (PreparedStatement upd = conn.prepareStatement(
                        "UPDATE players SET last_login = CURRENT_TIMESTAMP WHERE uuid = ?")) {
                    upd.setBytes(1, UuidUtils.asBytes(uuid));
                    upd.executeUpdate();
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

    public void closeSession(UUID uuid) {
        if (uuid == null) return;

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE player_sessions SET logout_at = CURRENT_TIMESTAMP WHERE player_uuid = ? AND logout_at IS NULL")) {

            ps.setBytes(1, UuidUtils.asBytes(uuid));
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public boolean hasOpenSession(UUID uuid) {
        if (uuid == null) return false;

        String sql = "SELECT 1 FROM player_sessions WHERE player_uuid = ? AND logout_at IS NULL LIMIT 1";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setBytes(1, UuidUtils.asBytes(uuid));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    public Long getLastLogoutMillis(UUID uuid) {
        if (uuid == null) return null;

        String sql = "SELECT MAX(logout_at) AS last_out FROM player_sessions WHERE player_uuid = ? AND logout_at IS NOT NULL";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setBytes(1, UuidUtils.asBytes(uuid));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Timestamp ts = rs.getTimestamp("last_out");
                return ts != null ? ts.getTime() : null;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }
}
