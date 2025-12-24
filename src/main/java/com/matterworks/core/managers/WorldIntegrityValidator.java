package com.matterworks.core.managers;

import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.domain.machines.registry.BlockRegistry;
import com.matterworks.core.domain.matter.MatterColor;
import com.matterworks.core.model.PlotObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

public class WorldIntegrityValidator {

    private final DatabaseManager db;
    private final BlockRegistry registry;

    public WorldIntegrityValidator(DatabaseManager db, BlockRegistry registry) {
        this.db = db;
        this.registry = registry;
    }

    public boolean validateWorldIntegrity() {
        System.out.println("🔍 [System] Avvio Validazione Integrità...");

        Map<Long, List<PlotObject>> machinesByPlot = loadAllMachines();
        Map<Long, Map<GridPosition, MatterColor>> resourcesByPlot = loadAllResources();

        Map<Long, Set<Long>> toDeleteByPlot = new HashMap<>();
        List<String> errorLog = new ArrayList<>();

        for (var entry : machinesByPlot.entrySet()) {
            Long plotId = entry.getKey();
            List<PlotObject> machines = entry.getValue();
            Map<GridPosition, MatterColor> resources = resourcesByPlot.getOrDefault(plotId, Collections.emptyMap());

            detectOverlaps(plotId, machines, errorLog, toDeleteByPlot);

            for (PlotObject m : machines) {
                if ("drill_mk1".equals(m.getTypeId())) {
                    GridPosition pos = new GridPosition(m.getX(), m.getY(), m.getZ());
                    if (!resources.containsKey(new GridPosition(pos.x(), 0, pos.z()))) {
                        errorLog.add("❌ Plot " + plotId + ": Trivella (ID " + m.getId() + ") piazzata nel vuoto a " + pos);
                    }
                }
            }
        }

        boolean hadOverlaps = toDeleteByPlot.values().stream().anyMatch(s -> s != null && !s.isEmpty());
        if (hadOverlaps) {
            printErrors(errorLog);

            int deleted = 0;
            int plotsFixed = 0;
            for (var e : toDeleteByPlot.entrySet()) {
                Long plotId = e.getKey();
                Set<Long> ids = e.getValue();
                if (ids == null || ids.isEmpty()) continue;

                int d = healOverlapsInDb(plotId, ids);
                if (d > 0) {
                    deleted += d;
                    plotsFixed++;
                }
            }

            System.out.println("🧹 [System] Self-Heal completato: rimossi " + deleted + " record corrotti su " + plotsFixed + " plot.");
            System.out.println("🔁 [System] Ri-validazione post-heal...");

            return validateWorldIntegrityNoHeal();
        }

        if (errorLog.isEmpty()) {
            System.out.println("✅ [System] Integrità verificata: 0 conflitti.");
            return true;
        }

        printErrors(errorLog);
        return false;
    }

    private boolean validateWorldIntegrityNoHeal() {
        Map<Long, List<PlotObject>> machinesByPlot = loadAllMachines();
        Map<Long, Map<GridPosition, MatterColor>> resourcesByPlot = loadAllResources();

        List<String> errorLog = new ArrayList<>();

        for (var entry : machinesByPlot.entrySet()) {
            Long plotId = entry.getKey();
            List<PlotObject> machines = entry.getValue();
            Map<GridPosition, MatterColor> resources = resourcesByPlot.getOrDefault(plotId, Collections.emptyMap());

            detectOverlaps(plotId, machines, errorLog, null);

            for (PlotObject m : machines) {
                if ("drill_mk1".equals(m.getTypeId())) {
                    GridPosition pos = new GridPosition(m.getX(), m.getY(), m.getZ());
                    if (!resources.containsKey(new GridPosition(pos.x(), 0, pos.z()))) {
                        errorLog.add("❌ Plot " + plotId + ": Trivella (ID " + m.getId() + ") piazzata nel vuoto a " + pos);
                    }
                }
            }
        }

        if (errorLog.isEmpty()) {
            System.out.println("✅ [System] Integrità verificata: 0 conflitti.");
            return true;
        }

        printErrors(errorLog);
        return false;
    }

    private void detectOverlaps(
            Long plotId,
            List<PlotObject> machines,
            List<String> errorLog,
            Map<Long, Set<Long>> toDeleteByPlot
    ) {
        if (machines == null || machines.isEmpty()) return;

        ArrayList<PlotObject> sorted = new ArrayList<>(machines);
        sorted.sort(Comparator.comparingLong(PlotObject::getId).reversed());

        Map<GridPosition, Long> occupiedBy = new HashMap<>();

        for (PlotObject m : sorted) {
            if (m == null || m.getTypeId() == null || m.getId() == null) continue;

            Vector3Int dim;
            try {
                dim = registry.getDimensions(m.getTypeId());
            } catch (Throwable t) {
                dim = Vector3Int.one();
            }

            boolean conflict = false;

            for (int x = 0; x < dim.x(); x++) {
                for (int y = 0; y < dim.y(); y++) {
                    for (int z = 0; z < dim.z(); z++) {
                        GridPosition p = new GridPosition(m.getX() + x, m.getY() + y, m.getZ() + z);
                        Long winnerId = occupiedBy.get(p);
                        if (winnerId != null) {
                            conflict = true;
                            errorLog.add("⚠️ Plot " + plotId + ": Conflitto a " + p + " tra ID " + m.getId() + " e ID " + winnerId);
                        }
                    }
                }
            }

            if (conflict) {
                if (toDeleteByPlot != null) {
                    toDeleteByPlot.computeIfAbsent(plotId, k -> new LinkedHashSet<>()).add(m.getId());
                }
                continue;
            }

            for (int x = 0; x < dim.x(); x++) {
                for (int y = 0; y < dim.y(); y++) {
                    for (int z = 0; z < dim.z(); z++) {
                        GridPosition p = new GridPosition(m.getX() + x, m.getY() + y, m.getZ() + z);
                        occupiedBy.put(p, m.getId());
                    }
                }
            }
        }
    }

    private int healOverlapsInDb(Long plotId, Set<Long> machineIdsToDelete) {
        if (plotId == null || machineIdsToDelete == null || machineIdsToDelete.isEmpty()) return 0;

        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement lock = conn.prepareStatement("SELECT id FROM plots WHERE id = ? FOR UPDATE")) {
                    lock.setLong(1, plotId);
                    lock.executeQuery();
                }

                int deleted = 0;
                try (PreparedStatement del = conn.prepareStatement("DELETE FROM plot_machines WHERE id = ?")) {
                    for (Long id : machineIdsToDelete) {
                        if (id == null) continue;
                        del.setLong(1, id);
                        del.addBatch();
                    }
                    int[] res = del.executeBatch();
                    for (int r : res) {
                        if (r > 0) deleted += r;
                    }
                }

                conn.commit();
                return deleted;
            } catch (Exception ex) {
                conn.rollback();
                System.err.println("🚨 [System] Self-Heal fallito per plotId=" + plotId + " -> rollback");
                ex.printStackTrace();
                return 0;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    private void printErrors(List<String> errorLog) {
        if (errorLog == null || errorLog.isEmpty()) return;

        if (errorLog.size() > 100) {
            System.err.println("🚨 [System] ATTENZIONE: Rilevate " + errorLog.size() + " collisioni nel mondo! (Troppe per la lista)");
        } else {
            System.err.println("🚨 [System] RILEVATE COLLISIONI:");
            for (String s : errorLog) System.err.println(s);
        }
    }

    private Map<Long, List<PlotObject>> loadAllMachines() {
        Map<Long, List<PlotObject>> result = new HashMap<>();
        String sql = "SELECT id, plot_id, x, y, z, type_id FROM plot_machines";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                PlotObject obj = new PlotObject(
                        rs.getLong("id"),
                        rs.getLong("plot_id"),
                        rs.getInt("x"),
                        rs.getInt("y"),
                        rs.getInt("z"),
                        rs.getString("type_id"),
                        null
                );
                result.computeIfAbsent(obj.getPlotId(), k -> new ArrayList<>()).add(obj);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }

    private Map<Long, Map<GridPosition, MatterColor>> loadAllResources() {
        Map<Long, Map<GridPosition, MatterColor>> result = new HashMap<>();
        String sql = "SELECT plot_id, x, z, resource_type FROM plot_resources";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long pid = rs.getLong("plot_id");
                GridPosition pos = new GridPosition(rs.getInt("x"), 0, rs.getInt("z"));
                MatterColor color = MatterColor.valueOf(rs.getString("resource_type"));
                result.computeIfAbsent(pid, k -> new HashMap<>()).put(pos, color);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }
}
