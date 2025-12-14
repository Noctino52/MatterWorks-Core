package com.matterworks.core.synchronization;

import com.matterworks.core.domain.machines.PlacedMachine;
import com.matterworks.core.managers.GridManager;
import com.matterworks.core.model.PlotObject;
import com.matterworks.core.ports.IRepository;
import com.matterworks.core.common.GridPosition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Servizio di Auto-Save.
 * - GridSaverService.
 */
public class GridSaverService {

    private final GridManager gridManager;
    private final IRepository repository;

    public GridSaverService(GridManager gridManager, IRepository repository) {
        this.gridManager = gridManager;
        this.repository = repository;
    }

    /**
     * Eseguito da uno scheduler o manualmente.
     * autoSaveTask
     */
    public void autoSaveTask() {
        Map<GridPosition, PlacedMachine> snapshot = gridManager.getSnapshot();

        // Raggruppa le macchine per Proprietario (per fare salvataggi batch per plot)
        Map<UUID, List<PlacedMachine>> byOwner = snapshot.values().stream()
                .filter(PlacedMachine::isDirty) // Salva solo quelle cambiate
                .collect(Collectors.groupingBy(PlacedMachine::getOwnerId));

        if (byOwner.isEmpty()) return;

        System.out.println("💾 AutoSave: Saving dirty machines for " + byOwner.size() + " plots.");

        byOwner.forEach((ownerId, machines) -> {
            // 1. Converti Domain -> DTO
            List<PlotObject> dtos = new ArrayList<>();
            for (PlacedMachine m : machines) {
                // Creiamo un DTO con i dati aggiornati (specialmente il metadata JSON)
                PlotObject dto = new PlotObject(
                        m.getDbId(),
                        null, // PlotID gestito internamente dal Repository/DAO tramite owner o DB ID
                        m.getPos().x(), m.getPos().y(), m.getPos().z(),
                        m.getTypeId(),
                        m.serialize()   // JSON stato aggiornato
                );
                dtos.add(dto);
            }

            // 2. Salva tramite Repository
            repository.savePlotMachines(ownerId, dtos);

            // 3. Pulisci dirty flag
            machines.forEach(PlacedMachine::clearDirty);
        });
    }
}
