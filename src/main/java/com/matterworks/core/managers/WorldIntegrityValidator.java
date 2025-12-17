package com.matterworks.core.managers;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.matterworks.core.common.Direction;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.domain.machines.BlockRegistry;
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
        System.out.println("🔍 Esecuzione Validazione Integrità Mondo...");
        Map<Long, List<PlotObject>> machinesByPlot = loadAllMachines();
        boolean hasConflicts = false;

        for (Map.Entry<Long, List<PlotObject>> entry : machinesByPlot.entrySet()) {
            Long plotId = entry.getKey();
            List<PlotObject> machines = entry.getValue();
            if (!validatePlot(plotId, machines)) {
                hasConflicts = true;
            }
        }

        if (hasConflicts) {
            System.err.println("❌ VALIDAZIONE FALLITA: Trovati conflitti di sovrapposizione!");
        } else {
            System.out.println("✅ Integrità Mondo Verificata: Nessuna collisione rilevata.");
        }
        return !hasConflicts;
    }

    private boolean validatePlot(Long plotId, List<PlotObject> machines) {
        // Mappa: Posizione -> ID Macchina (per sapere CHI occupa cosa)
        Map<GridPosition, Long> occupiedCells = new HashMap<>();
        boolean plotValid = true;

        for (PlotObject machine : machines) {
            String typeId = machine.getTypeId();
            Vector3Int baseDim = registry.getDimensions(typeId);

            // --- FIX: CALCOLO DIMENSIONI REALI (ROTAZIONE) ---
            Vector3Int effectiveDim = baseDim;
            try {
                // Leggiamo la rotazione dai metadati grezzi
                if (machine.getMetaData() != null && machine.getMetaData().has("orientation")) {
                    String orStr = machine.getMetaData().get("orientation").getAsString();
                    Direction dir = Direction.valueOf(orStr);
                    if (dir == Direction.EAST || dir == Direction.WEST) {
                        effectiveDim = new Vector3Int(baseDim.z(), baseDim.y(), baseDim.x());
                    }
                }
            } catch (Exception e) {
                // Fallback a dimensioni base se json corrotto
            }

            GridPosition origin = new GridPosition(machine.getX(), machine.getY(), machine.getZ());

            for (int x = 0; x < effectiveDim.x(); x++) {
                for (int y = 0; y < effectiveDim.y(); y++) {
                    for (int z = 0; z < effectiveDim.z(); z++) {
                        GridPosition pos = new GridPosition(origin.x() + x, origin.y() + y, origin.z() + z);

                        if (occupiedCells.containsKey(pos)) {
                            Long otherId = occupiedCells.get(pos);
                            // Se ID diverso, è conflitto
                            if (!otherId.equals(machine.getId())) {
                                System.err.println("   ⚠️ CONFLITTO nel Plot ID " + plotId + ":");
                                System.err.println("      Macchina A (ID " + machine.getId() + ") " + typeId + " a " + origin);
                                System.err.println("      Macchina B (ID " + otherId + ") occupa già " + pos);
                                plotValid = false;
                            }
                        } else {
                            occupiedCells.put(pos, machine.getId());
                        }
                    }
                }
            }
        }
        return plotValid;
    }

    private Map<Long, List<PlotObject>> loadAllMachines() {
        Map<Long, List<PlotObject>> result = new HashMap<>();
        // FIX: Carichiamo anche i metadati per la rotazione!
        String sql = "SELECT id, plot_id, x, y, z, type_id, metadata FROM plot_machines";

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                PlotObject obj = new PlotObject();
                obj.setId(rs.getLong("id"));
                obj.setPlotId(rs.getLong("plot_id"));
                obj.setPosition(rs.getInt("x"), rs.getInt("y"), rs.getInt("z"));
                obj.setTypeId(rs.getString("type_id"));

                String metaStr = rs.getString("metadata");
                if (metaStr != null) obj.setMetaDataFromString(metaStr);

                result.computeIfAbsent(obj.getPlotId(), k -> new ArrayList<>()).add(obj);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }
}