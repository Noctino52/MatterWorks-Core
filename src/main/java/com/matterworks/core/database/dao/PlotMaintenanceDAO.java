package com.matterworks.core.database.dao;

import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.database.UuidUtils;
import com.matterworks.core.domain.machines.base.PlacedMachine;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

public class PlotMaintenanceDAO {

    private final DatabaseManager db;

    public PlotMaintenanceDAO(DatabaseManager db) {
        this.db = db;
    }

    public int getPlotItemsPlaced(UUID ownerId) {
        if (ownerId == null) return 0;

        Long plotId = findPlotIdByOwner(ownerId);
        if (plotId == null) return 0;

        String sql = "SELECT item_placed FROM plots WHERE id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, plotId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Math.max(0, rs.getInt("item_placed"));
            }
        } catch (SQLException e) {
            if (!SqlCompat.isUnknownColumn(e)) {
                e.printStackTrace();
            } else {
                return countPlotMachines(plotId);
            }
        }
        return 0;
    }

    public void updateMachinesMetadata(List<PlacedMachine> machines) {
        if (machines == null || machines.isEmpty()) return;

        Map<UUID, List<PlacedMachine>> byOwner = machines.stream()
                .filter(Objects::nonNull)
                .filter(m -> m.getDbId() != null)
                .collect(Collectors.groupingBy(PlacedMachine::getOwnerId));

        for (Map.Entry<UUID, List<PlacedMachine>> entry : byOwner.entrySet()) {
            UUID ownerId = entry.getKey();
            List<PlacedMachine> list = entry.getValue();
            if (ownerId == null || list.isEmpty()) continue;

            String sql = "UPDATE plot_machines SET metadata = ? WHERE id = ?";

            try (Connection conn = db.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                for (PlacedMachine pm : list) {
                    stmt.setString(1, pm.serialize().toString());
                    stmt.setLong(2, pm.getDbId());
                    stmt.addBatch();
                }
                stmt.executeBatch();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to update machines metadata for ownerId=" + ownerId, e);
            }
        }
    }

    public void clearPlotData(UUID ownerId) {
        if (ownerId == null) return;

        Long plotId = findPlotIdByOwner(ownerId);
        if (plotId == null) return;

        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM plot_machines WHERE plot_id = ?")) {
                    stmt.setLong(1, plotId);
                    stmt.executeUpdate();
                }

                // ❌ NON TOCCARE unlocked_extra_x / unlocked_extra_y

                try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM plot_resources WHERE plot_id = ?")) {
                    stmt.setLong(1, plotId);
                    stmt.executeUpdate();
                }

                try (PreparedStatement stmt = conn.prepareStatement("UPDATE plots SET item_placed = 0 WHERE id = ?")) {
                    stmt.setLong(1, plotId);
                    stmt.executeUpdate();
                } catch (SQLException e) {
                    if (!SqlCompat.isUnknownColumn(e)) throw e;
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

    public void deletePlayerFull(UUID uuid) {
        if (uuid == null) return;

        Long plotId = findPlotIdByOwner(uuid);

        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (plotId != null) {
                    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM plot_machines WHERE plot_id = ?")) {
                        ps.setLong(1, plotId);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM plot_resources WHERE plot_id = ?")) {
                        ps.setLong(1, plotId);
                        ps.executeUpdate();
                    }

                    // best-effort reset contatore
                    try (PreparedStatement ps = conn.prepareStatement("UPDATE plots SET item_placed = 0 WHERE id = ?")) {
                        ps.setLong(1, plotId);
                        ps.executeUpdate();
                    } catch (SQLException e) {
                        if (!SqlCompat.isUnknownColumn(e)) throw e;
                    }

                    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM plots WHERE id = ?")) {
                        ps.setLong(1, plotId);
                        ps.executeUpdate();
                    }
                }

                byte[] uuidBytes = UuidUtils.asBytes(uuid);

                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM player_inventory WHERE player_uuid = ?")) {
                    ps.setBytes(1, uuidBytes);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM verification_codes WHERE player_uuid = ?")) {
                    ps.setBytes(1, uuidBytes);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM transactions WHERE player_uuid = ?")) {
                    ps.setBytes(1, uuidBytes);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM player_session WHERE player_uuid = ?")) {
                    ps.setBytes(1, uuidBytes);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM players WHERE uuid = ?")) {
                    ps.setBytes(1, uuidBytes);
                    ps.executeUpdate();
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

    // --------------------
    // helpers
    // --------------------

    private Long findPlotIdByOwner(UUID ownerId) {
        String sql = "SELECT id FROM plots WHERE owner_id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBytes(1, UuidUtils.asBytes(ownerId));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong("id");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    private int countPlotMachines(Long plotId) {
        if (plotId == null) return 0;
        String sql = "SELECT COUNT(*) AS c FROM plot_machines WHERE plot_id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, plotId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Math.max(0, rs.getInt("c"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }
}
