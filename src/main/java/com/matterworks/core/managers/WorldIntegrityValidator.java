package com.matterworks.core.managers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.matterworks.core.common.Direction;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.domain.machines.registry.BlockRegistry;
import com.matterworks.core.domain.matter.MatterColor;

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

    private static final class MachineRow {
        final long id;
        final long plotId;
        final int x;
        final int y;
        final int z;
        final String typeId;
        final JsonObject meta;

        private MachineRow(long id, long plotId, int x, int y, int z, String typeId, JsonObject meta) {
            this.id = id;
            this.plotId = plotId;
            this.x = x;
            this.y = y;
            this.z = z;
            this.typeId = typeId;
            this.meta = (meta != null) ? meta : new JsonObject();
        }
    }

    public boolean validateWorldIntegrity() {
        System.out.println("[System] Starting World Integrity Validation...");

        Map<Long, List<MachineRow>> machinesByPlot = loadAllMachines();
        Map<Long, Map<GridPosition, MatterColor>> resourcesByPlot = loadAllResources();

        Map<Long, Set<Long>> toDeleteByPlot = new HashMap<>();
        List<String> errorLog = new ArrayList<>();

        for (var entry : machinesByPlot.entrySet()) {
            Long plotId = entry.getKey();
            List<MachineRow> machines = entry.getValue();
            Map<GridPosition, MatterColor> resources = resourcesByPlot.getOrDefault(plotId, Collections.emptyMap());

            detectOverlaps(plotId, machines, errorLog, toDeleteByPlot);

            // Drill placed over void (legacy + new id)
            for (MachineRow m : machines) {
                if (m == null) continue;
                if (isDrillType(m.typeId)) {
                    GridPosition pos = new GridPosition(m.x, m.y, m.z);
                    if (!resources.containsKey(new GridPosition(pos.x(), 0, pos.z()))) {
                        errorLog.add("ERROR Plot " + plotId + ": Drill (ID " + m.id + ") placed on void at " + pos);
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

            System.out.println("[System] Self-Heal completed: deleted " + deleted + " corrupted rows across " + plotsFixed + " plots.");
            System.out.println("[System] Re-validating after heal...");

            return validateWorldIntegrityNoHeal();
        }

        if (errorLog.isEmpty()) {
            System.out.println("[System] Integrity OK: 0 conflicts.");
            return true;
        }

        printErrors(errorLog);
        return false;
    }

    private boolean validateWorldIntegrityNoHeal() {
        Map<Long, List<MachineRow>> machinesByPlot = loadAllMachines();
        Map<Long, Map<GridPosition, MatterColor>> resourcesByPlot = loadAllResources();

        List<String> errorLog = new ArrayList<>();

        for (var entry : machinesByPlot.entrySet()) {
            Long plotId = entry.getKey();
            List<MachineRow> machines = entry.getValue();
            Map<GridPosition, MatterColor> resources = resourcesByPlot.getOrDefault(plotId, Collections.emptyMap());

            detectOverlaps(plotId, machines, errorLog, null);

            for (MachineRow m : machines) {
                if (m == null) continue;
                if (isDrillType(m.typeId)) {
                    GridPosition pos = new GridPosition(m.x, m.y, m.z);
                    if (!resources.containsKey(new GridPosition(pos.x(), 0, pos.z()))) {
                        errorLog.add("ERROR Plot " + plotId + ": Drill (ID " + m.id + ") placed on void at " + pos);
                    }
                }
            }
        }

        if (errorLog.isEmpty()) {
            System.out.println("[System] Integrity OK: 0 conflicts.");
            return true;
        }

        printErrors(errorLog);
        return false;
    }

    private boolean isDrillType(String typeId) {
        if (typeId == null) return false;
        return "drill".equals(typeId) || "drill".equals(typeId);
    }

    private void detectOverlaps(
            Long plotId,
            List<MachineRow> machines,
            List<String> errorLog,
            Map<Long, Set<Long>> toDeleteByPlot
    ) {
        if (machines == null || machines.isEmpty()) return;

        // Policy: highest ID wins
        ArrayList<MachineRow> sorted = new ArrayList<>(machines);
        sorted.sort(Comparator.comparingLong((MachineRow m) -> m.id).reversed());

        Map<GridPosition, Long> occupiedBy = new HashMap<>();

        for (MachineRow m : sorted) {
            if (m == null || m.typeId == null) continue;

            Vector3Int dim = getOrientedDimensions(m);
            boolean conflict = false;

            for (int dx = 0; dx < dim.x(); dx++) {
                for (int dy = 0; dy < dim.y(); dy++) {
                    for (int dz = 0; dz < dim.z(); dz++) {
                        GridPosition p = new GridPosition(m.x + dx, m.y + dy, m.z + dz);
                        Long winnerId = occupiedBy.get(p);
                        if (winnerId != null) {
                            conflict = true;
                            errorLog.add("WARN Plot " + plotId + ": Overlap at " + p + " between ID " + m.id + " and ID " + winnerId);
                        }
                    }
                }
            }

            if (conflict) {
                if (toDeleteByPlot != null) {
                    toDeleteByPlot.computeIfAbsent(plotId, k -> new LinkedHashSet<>()).add(m.id);
                }
                continue;
            }

            for (int dx = 0; dx < dim.x(); dx++) {
                for (int dy = 0; dy < dim.y(); dy++) {
                    for (int dz = 0; dz < dim.z(); dz++) {
                        GridPosition p = new GridPosition(m.x + dx, m.y + dy, m.z + dz);
                        occupiedBy.put(p, m.id);
                    }
                }
            }
        }
    }

    private Vector3Int getOrientedDimensions(MachineRow m) {
        Vector3Int base;
        try {
            base = registry.getDimensions(m.typeId);
            if (base == null) base = Vector3Int.one();
        } catch (Throwable t) {
            base = Vector3Int.one();
        }

        Direction ori = readOrientation(m.meta);

        if (ori == Direction.EAST || ori == Direction.WEST) {
            return new Vector3Int(base.z(), base.y(), base.x());
        }
        return base;
    }

    private Direction readOrientation(JsonObject meta) {
        try {
            if (meta == null) return Direction.NORTH;
            JsonElement el = meta.get("orientation");
            if (el == null || el.isJsonNull()) return Direction.NORTH;
            String s = el.getAsString();
            if (s == null || s.isBlank()) return Direction.NORTH;
            return Direction.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return Direction.NORTH;
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
                    for (int r : res) if (r > 0) deleted += r;
                }

                conn.commit();
                return deleted;
            } catch (Exception ex) {
                conn.rollback();
                System.err.println("[System] Self-Heal failed for plotId=" + plotId + " -> rollback");
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
            System.err.println("[System] WARNING: detected " + errorLog.size() + " collisions (too many to print).");
        } else {
            System.err.println("[System] COLLISIONS DETECTED:");
            for (String s : errorLog) System.err.println(s);
        }
    }

    private Map<Long, List<MachineRow>> loadAllMachines() {
        Map<Long, List<MachineRow>> result = new HashMap<>();
        String sql = "SELECT id, plot_id, x, y, z, type_id, metadata FROM plot_machines";

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                long id = rs.getLong("id");
                long plotId = rs.getLong("plot_id");
                int x = rs.getInt("x");
                int y = rs.getInt("y");
                int z = rs.getInt("z");
                String typeId = rs.getString("type_id");

                String metaStr = rs.getString("metadata");
                JsonObject meta = parseMeta(metaStr);

                MachineRow row = new MachineRow(id, plotId, x, y, z, typeId, meta);
                result.computeIfAbsent(plotId, k -> new ArrayList<>()).add(row);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return result;
    }

    private JsonObject parseMeta(String jsonString) {
        if (jsonString == null || jsonString.isEmpty()) return new JsonObject();
        try {
            return JsonParser.parseString(jsonString).getAsJsonObject();
        } catch (Exception e) {
            System.err.println("[System] ERROR parsing plot_machines.metadata JSON: " + e.getMessage());
            return new JsonObject();
        }
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
