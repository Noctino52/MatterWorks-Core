package com.matterworks.core.database.dao;

import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.database.UuidUtils;
import com.matterworks.core.models.PlotModel; // Assumiamo tu abbia creato un semplice POJO PlotModel

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public class PlotDAO {

    private final DatabaseManager db;

    // Crea un nuovo plot assegnato a un player
    private static final String INSERT_SQL = """
        INSERT INTO plots (owner_id, allocation_index, world_x, world_z, expansion_tier, is_active)
        VALUES (?, ?, ?, ?, 1, TRUE)
    """;

    // Trova il plot attivo di un player
    private static final String FIND_BY_OWNER_SQL = """
        SELECT * FROM plots WHERE owner_id = ? AND is_active = TRUE LIMIT 1
    """;

    // Serve per la "Void Math": qual è l'ultimo numero assegnato?
    private static final String MAX_INDEX_SQL = "SELECT MAX(allocation_index) as max_idx FROM plots";

    public PlotDAO(DatabaseManager db) {
        this.db = db;
    }

    public void createPlot(UUID ownerId, int index, int x, int z) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {

            ps.setBytes(1, UuidUtils.asBytes(ownerId));
            ps.setInt(2, index);
            ps.setInt(3, x);
            ps.setInt(4, z);

            ps.executeUpdate();

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public PlotModel loadPlot(UUID ownerId) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(FIND_BY_OWNER_SQL)) {

            ps.setBytes(1, UuidUtils.asBytes(ownerId));

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new PlotModel(
                            rs.getLong("id"),
                            ownerId,
                            rs.getInt("allocation_index"),
                            rs.getInt("world_x"),
                            rs.getInt("world_z")
                    );
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null; // Nessun plot trovato
    }

    /**
     * Ritorna il prossimo indice disponibile (0 se è il primo plot assoluto).
     */
    public int getNextAllocationIndex() {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(MAX_INDEX_SQL);
             ResultSet rs = ps.executeQuery()) {

            if (rs.next()) {
                // Se max_idx è null (tabella vuota), ritorna 0. Altrimenti max + 1
                int currentMax = rs.getInt("max_idx");
                if (rs.wasNull()) return 0;
                return currentMax + 1;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0; // Fallback
    }
}