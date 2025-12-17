package com.matterworks.core.managers;

import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.domain.machines.BlockRegistry;
import com.matterworks.core.domain.matter.MatterColor;
import com.matterworks.core.model.PlotObject;
import com.matterworks.core.infrastructure.MariaDBAdapter;

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

        List<String> errorLog = new ArrayList<>();

        for (var entry : machinesByPlot.entrySet()) {
            Long plotId = entry.getKey();
            List<PlotObject> machines = entry.getValue();
            Map<GridPosition, MatterColor> resources = resourcesByPlot.getOrDefault(plotId, Collections.emptyMap());

            // 1. Check Sovrapposizioni [cite: 713, 720]
            validateOverlaps(plotId, machines, errorLog);

            // 2. Check Trivelle su Vene
            for (PlotObject m : machines) {
                if (m.getTypeId().equals("drill_mk1")) {
                    GridPosition pos = new GridPosition(m.getX(), m.getY(), m.getZ());
                    if (!resources.containsKey(pos)) {
                        errorLog.add("❌ Plot " + plotId + ": Trivella (ID " + m.getId() + ") piazzata nel vuoto a " + pos);
                    }
                }
            }
        }

        if (errorLog.isEmpty()) {
            System.out.println("✅ [System] Integrità verificata: 0 conflitti.");
            return true;
        } else {
            if (errorLog.size() > 100) {
                System.err.println("🚨 [System] ATTENZIONE: Rilevate " + errorLog.size() + " collisioni nel mondo! (Troppe per la lista)");
            } else {
                System.err.println("🚨 [System] RILEVATE COLLISIONI:");
                errorLog.forEach(System.err::println);
            }
            return false;
        }
    }

    private void validateOverlaps(Long plotId, List<PlotObject> machines, List<String> errorLog) {
        Map<GridPosition, Long> occupied = new HashMap<>();
        for (PlotObject m : machines) {
            Vector3Int dim = registry.getDimensions(m.getTypeId());
            for (int x=0; x<dim.x(); x++) for (int y=0; y<dim.y(); y++) for (int z=0; z<dim.z(); z++) {
                GridPosition p = new GridPosition(m.getX()+x, m.getY()+y, m.getZ()+z);
                if (occupied.containsKey(p)) {
                    errorLog.add("⚠️ Plot " + plotId + ": Conflitto a " + p + " tra ID " + m.getId() + " e ID " + occupied.get(p));
                }
                occupied.put(p, m.getId());
            }
        }
    }

    // ... (loadAllMachines e loadAllResources rimangono uguali alla versione precedente)
    private Map<Long, List<PlotObject>> loadAllMachines() {
        Map<Long, List<PlotObject>> result = new HashMap<>();
        String sql = "SELECT id, plot_id, x, y, z, type_id FROM plot_machines";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                PlotObject obj = new PlotObject(rs.getLong("id"), rs.getLong("plot_id"), rs.getInt("x"), rs.getInt("y"), rs.getInt("z"), rs.getString("type_id"), null);
                result.computeIfAbsent(obj.getPlotId(), k -> new ArrayList<>()).add(obj);
            }
        } catch (Exception e) { e.printStackTrace(); }
        return result;
    }

    private Map<Long, Map<GridPosition, MatterColor>> loadAllResources() {
        Map<Long, Map<GridPosition, MatterColor>> result = new HashMap<>();
        String sql = "SELECT plot_id, x, z, resource_type FROM plot_resources";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long pid = rs.getLong("plot_id");
                GridPosition pos = new GridPosition(rs.getInt("x"), 0, rs.getInt("z"));
                MatterColor color = MatterColor.valueOf(rs.getString("resource_type"));
                result.computeIfAbsent(pid, k -> new HashMap<>()).put(pos, color);
            }
        } catch (Exception e) { e.printStackTrace(); }
        return result;
    }
}