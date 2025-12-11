package com.matterworks.core.database.dao;

import com.google.gson.Gson;
import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.models.PlotObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PlotObjectDAO {

    private final DatabaseManager db;
    private final Gson gson;

    // Inserisce una nuova macchina
    private static final String INSERT_SQL = """
        INSERT INTO plot_objects (plot_id, x, y, z, type_id, meta_data)
        VALUES (?, ?, ?, ?, ?, ?)
    """;

    // Carica tutto il plot in un colpo solo (Chunk loading veloce)
    private static final String SELECT_BY_PLOT_SQL = "SELECT * FROM plot_objects WHERE plot_id = ?";

    public PlotObjectDAO(DatabaseManager db) {
        this.db = db;
        this.gson = new Gson();
    }

    public void placeMachine(PlotObject machine) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {

            ps.setLong(1, machine.getPlotId());
            ps.setInt(2, machine.getX());
            ps.setInt(3, machine.getY());
            ps.setInt(4, machine.getZ());
            ps.setString(5, machine.getTypeId());
            ps.setString(6, gson.toJson(machine.getMetaData())); // JSON Blob

            ps.executeUpdate();

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public List<PlotObject> loadPlotMachines(long plotId) {
        List<PlotObject> machines = new ArrayList<>();

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BY_PLOT_SQL)) {

            ps.setLong(1, plotId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PlotObject obj = new PlotObject();
                    obj.setId(rs.getLong("id"));
                    obj.setPlotId(rs.getLong("plot_id"));
                    obj.setPosition(rs.getInt("x"), rs.getInt("y"), rs.getInt("z"));
                    obj.setTypeId(rs.getString("type_id"));

                    String metaJson = rs.getString("meta_data");
                    if (metaJson != null) {
                        obj.setMetaData(gson.fromJson(metaJson, Map.class));
                    }

                    machines.add(obj);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return machines;
    }
}