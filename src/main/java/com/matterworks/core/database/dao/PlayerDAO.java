package com.matterworks.core.database.dao;

import com.google.gson.Gson;
import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.database.UuidUtils; // La tua utility per i byte
import com.matterworks.core.models.PlayerModel; // La tua classe Player

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class PlayerDAO {

    private final DatabaseManager db;
    private final Gson gson;

    // L'SQL è definito qui come COSTANTE. Pulito e sicuro.
    private static final String UPSERT_SQL = """
        INSERT INTO players (uuid, username, money, void_coins, tech_unlocks, active_boosters, last_login)
        VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
        ON DUPLICATE KEY UPDATE
            money = VALUES(money),
            void_coins = VALUES(void_coins),
            tech_unlocks = VALUES(tech_unlocks),
            active_boosters = VALUES(active_boosters),
            last_login = CURRENT_TIMESTAMP
    """;

    private static final String SELECT_SQL = "SELECT * FROM players WHERE uuid = ?";

    public PlayerDAO(DatabaseManager db) {
        this.db = db;
        this.gson = new Gson();
    }

    /**
     * Salva o Aggiorna il player.
     * Uso: playerDao.save(mioPlayer);
     */
    public void save(PlayerModel player) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(UPSERT_SQL)) {

            // Mappatura manuale: 100% sicura e veloce
            ps.setBytes(1, UuidUtils.asBytes(player.getUuid()));
            ps.setString(2, player.getUsername());
            ps.setBigDecimal(3, player.getMoney());
            ps.setInt(4, player.getVoidCoins());
            // Convertiamo i set/liste in JSON
            ps.setString(5, gson.toJson(player.getTechUnlocks()));
            ps.setString(6, gson.toJson(player.getActiveBoosters()));

            ps.executeUpdate();

        } catch (SQLException e) {
            e.printStackTrace(); // In produzione usa un Logger
        }
    }

    /**
     * Carica il player. Restituisce null se non esiste.
     * Uso: PlayerModel p = playerDao.load(uuid);
     */
    public PlayerModel load(UUID uuid) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_SQL)) {

            ps.setBytes(1, UuidUtils.asBytes(uuid));

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapRow(rs);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    // Metodo helper per tenere pulito il codice di caricamento
    private PlayerModel mapRow(ResultSet rs) throws SQLException {
        UUID uuid = UuidUtils.asUuid(rs.getBytes("uuid"));
        String username = rs.getString("username");

        PlayerModel p = new PlayerModel(uuid, username);
        p.setMoney(rs.getBigDecimal("money"));
        p.setVoidCoins(rs.getInt("void_coins"));

        // Deserializzazione JSON sicura
        String techJson = rs.getString("tech_unlocks");
        if (techJson != null) {
            // Un po' di boilerplate per Gson e i generici, ma standard
            Set<String> techs = gson.fromJson(techJson, new com.google.gson.reflect.TypeToken<Set<String>>(){}.getType());
            p.setTechUnlocks(techs != null ? techs : new HashSet<>());
        }

        // Fai lo stesso per active_boosters se serve...

        return p;
    }
}