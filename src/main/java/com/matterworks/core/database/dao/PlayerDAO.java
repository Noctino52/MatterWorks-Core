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
        INSERT INTO players (
            uuid, username, money, void_coins, prestige_level, rank, tech_unlocks,
            overclock_start_playtime_seconds, overclock_duration_seconds, overclock_multiplier
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE
            username = VALUES(username),
            money = VALUES(money),
            void_coins = VALUES(void_coins),
            prestige_level = VALUES(prestige_level),
            rank = VALUES(rank),
            tech_unlocks = VALUES(tech_unlocks),
            overclock_start_playtime_seconds = VALUES(overclock_start_playtime_seconds),
            overclock_duration_seconds = VALUES(overclock_duration_seconds),
            overclock_multiplier = VALUES(overclock_multiplier)
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

            ps.setLong(8, p.getOverclockStartPlaytimeSeconds());
            ps.setLong(9, p.getOverclockDurationSeconds());
            ps.setDouble(10, p.getOverclockMultiplier());

            ps.executeUpdate();

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public java.util.List<PlayerProfile> loadAll() {
        String sql = "SELECT * FROM players";
        java.util.List<PlayerProfile> out = new java.util.ArrayList<>();

        try (java.sql.Connection conn = db.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(sql);
             java.sql.ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                java.util.UUID uuid = com.matterworks.core.database.UuidUtils.asUuid(rs.getBytes("uuid"));
                if (uuid == null) continue;

                PlayerProfile p = new PlayerProfile(uuid);
                p.setUsername(rs.getString("username"));
                p.setMoney(rs.getDouble("money"));
                p.setVoidCoins(rs.getInt("void_coins"));
                p.setPrestigeLevel(rs.getInt("prestige_level"));

                String rankStr = rs.getString("rank");
                if (rankStr != null) {
                    try { p.setRank(PlayerProfile.PlayerRank.valueOf(rankStr)); }
                    catch (Exception ignored) { p.setRank(PlayerProfile.PlayerRank.PLAYER); }
                }

                String techJson = rs.getString("tech_unlocks");
                if (techJson != null && !techJson.isBlank()) {
                    try {
                        java.lang.reflect.Type setType =
                                new com.google.gson.reflect.TypeToken<java.util.HashSet<String>>() {}.getType();
                        java.util.Set<String> unlocks = gson.fromJson(techJson, setType);
                        if (unlocks != null) unlocks.forEach(p::addTech);
                    } catch (Exception ex) {
                        System.err.println("Warning: tech_unlocks invalid for " + uuid + " -> using empty set.");
                    }
                }

                // Overclock columns (ignore if older schema)
                try {
                    p.setOverclockStartPlaytimeSeconds(rs.getLong("overclock_start_playtime_seconds"));
                    p.setOverclockDurationSeconds(rs.getLong("overclock_duration_seconds"));
                    p.setOverclockMultiplier(rs.getDouble("overclock_multiplier"));
                } catch (java.sql.SQLException ignored) { }

                out.add(p);
            }

        } catch (java.sql.SQLException e) {
            e.printStackTrace();
        }

        return out;
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
                }

                String techJson = rs.getString("tech_unlocks");
                if (techJson != null && !techJson.isBlank()) {
                    try {
                        Type setType = new TypeToken<HashSet<String>>(){}.getType();
                        Set<String> unlocks = gson.fromJson(techJson, setType);
                        if (unlocks != null) unlocks.forEach(p::addTech);
                    } catch (Exception ex) {
                        System.err.println("Warning: tech_unlocks invalid for " + uuid + " -> using empty set.");
                    }
                }

                // Overclock (schema may not have columns on older DB)
                try {
                    p.setOverclockStartPlaytimeSeconds(rs.getLong("overclock_start_playtime_seconds"));
                    p.setOverclockDurationSeconds(rs.getLong("overclock_duration_seconds"));
                    p.setOverclockMultiplier(rs.getDouble("overclock_multiplier"));
                } catch (SQLException ignored) {
                    // ignore older schema
                }

                return p;
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    // ==========================================================
    // PLAYTIME (seconds) from player_sessions (includes open session)
    // ==========================================================
    // inside PlayerDAO

    private static final java.util.concurrent.atomic.AtomicBoolean warnedMissingPlayerSessionTable =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public long getTotalPlaytimeSeconds(UUID uuid) {
        if (uuid == null) return 0L;

        // NOTE: table name in your schema is `player_session` (singular)
        // We prefer `session_seconds` if present, otherwise compute from login/logout.
        String sql = """
        SELECT COALESCE(SUM(
            COALESCE(session_seconds,
                     TIMESTAMPDIFF(SECOND, login_at, COALESCE(logout_at, CURRENT_TIMESTAMP)))
        ), 0) AS total_seconds
        FROM player_session
        WHERE player_uuid = ?
    """;

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setBytes(1, UuidUtils.asBytes(uuid));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return 0L;
                long v = rs.getLong("total_seconds");
                return Math.max(0L, v);
            }

        } catch (SQLException e) {
            // If someone runs an older schema without player_session, don't spam every tick
            // Error: 1146 (table doesn't exist)
            if ("42S02".equalsIgnoreCase(e.getSQLState()) || e.getMessage().contains("1146")) {
                if (warnedMissingPlayerSessionTable.compareAndSet(false, true)) {
                    System.err.println("[Overclock] Warning: table `player_session` not found. Playtime will be 0 until DB schema is updated.");
                }
                return 0L;
            }

            e.printStackTrace();
            return 0L;
        }
    }

}
