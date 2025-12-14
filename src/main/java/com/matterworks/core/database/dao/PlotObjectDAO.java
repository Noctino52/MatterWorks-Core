package com.matterworks.core.database.dao;

import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.model.PlotObject;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class PlotObjectDAO {

    private final DatabaseManager db;

    // --- FIX: USIAMO UPSERT INVECE DI INSERT ---
    // Se la combinazione (plot_id, x, y, z) esiste già (Unique Key),
    // aggiorniamo solo i metadati e il tipo, invece di dare errore.
    private static final String UPSERT_SQL = """
        INSERT INTO plot_objects (plot_id, x, y, z, type_id, meta_data)
        VALUES (?, ?, ?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE 
            meta_data = VALUES(meta_data),
            type_id = VALUES(type_id)
    """;

    private static final String SELECT_SQL = "SELECT * FROM plot_objects WHERE plot_id = ?";
    private static final String DELETE_SQL = "DELETE FROM plot_objects WHERE id = ?";

    public PlotObjectDAO(DatabaseManager db) {
        this.db = db;
    }

    public void placeMachine(PlotObject obj) {
        // Usiamo Statement.RETURN_GENERATED_KEYS per ottenere l'ID se è un nuovo inserimento
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(UPSERT_SQL, Statement.RETURN_GENERATED_KEYS)) {

            ps.setLong(1, obj.getPlotId());
            ps.setInt(2, obj.getX());
            ps.setInt(3, obj.getY());
            ps.setInt(4, obj.getZ());
            ps.setString(5, obj.getTypeId());
            // Gestione metadati sicura
            ps.setString(6, obj.getMetaData() != null ? obj.getMetaData().toString() : "{}");

            ps.executeUpdate();

            // Se è stato un INSERT (nuova macchina), prendiamo l'ID.
            // Se è stato un UPDATE, l'ID non cambia (lo abbiamo già in obj.id solitamente).
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    long newId = rs.getLong(1);
                    // Aggiorniamo l'ID solo se ne abbiamo ricevuto uno valido (cioè era un insert)
                    if (newId > 0) {
                        obj.setId(newId);
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public List<PlotObject> loadPlotMachines(Long plotId) {
        List<PlotObject> machines = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_SQL)) {

            ps.setLong(1, plotId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PlotObject obj = new PlotObject();
                    obj.setId(rs.getLong("id"));
                    obj.setPlotId(plotId);
                    obj.setX(rs.getInt("x"));
                    obj.setY(rs.getInt("y"));
                    obj.setZ(rs.getInt("z"));
                    obj.setTypeId(rs.getString("type_id"));

                    // Parsing JSON sicuro
                    String metaStr = rs.getString("meta_data");
                    if (metaStr != null) {
                        obj.setMetaDataFromString(metaStr);
                    }

                    machines.add(obj);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return machines;
    }

    public void deleteMachine(Long dbId) {
        if (dbId == null) return;

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(DELETE_SQL)) {

            ps.setLong(1, dbId);
            ps.executeUpdate();
            System.out.println("🗑️ DB: Cancellata riga ID " + dbId);

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}