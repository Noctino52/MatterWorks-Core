package com.matterworks.core.managers;

import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.domain.machines.BlockRegistry;
import com.matterworks.core.model.PlotObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

/**
 * Servizio di Diagnostica all'Avvio.
 * Verifica che le nuove dimensioni nel DB non creino collisioni con piazzamenti esistenti.
 */
public class WorldIntegrityValidator {

    private final DatabaseManager db;
    private final BlockRegistry registry;

    public WorldIntegrityValidator(DatabaseManager db, BlockRegistry registry) {
        this.db = db;
        this.registry = registry;
    }

    /**
     * Esegue la scansione completa.
     * @return true se il mondo è valido, false se ci sono conflitti critici.
     */
    public boolean validateWorldIntegrity() {
        System.out.println("🔍 Esecuzione Validazione Integrità Mondo...");

        // 1. Carica TUTTE le macchine dal DB (Query grezza per velocità)
        Map<Long, List<PlotObject>> machinesByPlot = loadAllMachines();
        boolean hasConflicts = false;

        // 2. Itera Plot per Plot (Le collisioni avvengono solo dentro lo stesso plot)
        for (Map.Entry<Long, List<PlotObject>> entry : machinesByPlot.entrySet()) {
            Long plotId = entry.getKey();
            List<PlotObject> machines = entry.getValue();

            if (!validatePlot(plotId, machines)) {
                hasConflicts = true;
            }
        }

        if (hasConflicts) {
            System.err.println("❌ VALIDAZIONE FALLITA: Trovati conflitti di sovrapposizione!");
            System.err.println("   Il server potrebbe avviarsi con macchine compenetrate.");
        } else {
            System.out.println("✅ Integrità Mondo Verificata: Nessuna collisione rilevata.");
        }

        return !hasConflicts;
    }

    private boolean validatePlot(Long plotId, List<PlotObject> machines) {
        // Usiamo un Set per tracciare le posizioni occupate in questo plot
        Set<GridPosition> occupiedPositions = new HashSet<>();
        boolean plotValid = true;

        for (PlotObject machine : machines) {
            String typeId = machine.getTypeId();
            Vector3Int dim = registry.getDimensions(typeId); // Qui usa le dimensioni NUOVE dal DB

            GridPosition origin = new GridPosition(machine.getX(), machine.getY(), machine.getZ());

            // Simuliamo l'occupazione spaziale
            for (int x = 0; x < dim.x(); x++) {
                for (int y = 0; y < dim.y(); y++) {
                    for (int z = 0; z < dim.z(); z++) {
                        GridPosition pos = new GridPosition(origin.x() + x, origin.y() + y, origin.z() + z);

                        if (occupiedPositions.contains(pos)) {
                            System.err.println("   ⚠️ CONFLITTO nel Plot ID " + plotId + ":");
                            System.err.println("      Macchina '" + typeId + "' a " + origin);
                            System.err.println("      Invade lo spazio già occupato a " + pos);
                            plotValid = false;
                        } else {
                            occupiedPositions.add(pos);
                        }
                    }
                }
            }
        }
        return plotValid;
    }

    private Map<Long, List<PlotObject>> loadAllMachines() {
        Map<Long, List<PlotObject>> result = new HashMap<>();
        String sql = "SELECT * FROM plot_objects"; // Carica tutto (in produzione usare paginazione se enorme)

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                PlotObject obj = new PlotObject();
                obj.setId(rs.getLong("id"));
                obj.setPlotId(rs.getLong("plot_id"));
                obj.setPosition(rs.getInt("x"), rs.getInt("y"), rs.getInt("z"));
                obj.setTypeId(rs.getString("type_id"));

                result.computeIfAbsent(obj.getPlotId(), k -> new ArrayList<>()).add(obj);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }
}