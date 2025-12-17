package com.matterworks.core.synchronization;

import com.matterworks.core.common.GridPosition; // <--- IMPORT AGGIUNTO
import com.matterworks.core.domain.machines.PlacedMachine;
import com.matterworks.core.managers.GridManager;
import com.matterworks.core.ports.IRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class GridSaverService {

    private final GridManager gridManager;
    private final IRepository repository;

    public GridSaverService(GridManager gridManager, IRepository repository) {
        this.gridManager = gridManager;
        this.repository = repository;
    }

    public void autoSaveTask() {
        // Recupera tutte le macchine
        Map<GridPosition, PlacedMachine> allMachines = gridManager.getSnapshot();

        // Filtra quelle "sporche" (modificate)
        List<PlacedMachine> dirtyMachines = allMachines.values().stream()
                .filter(PlacedMachine::isDirty)
                .distinct()
                .collect(Collectors.toList());

        if (dirtyMachines.isEmpty()) return;

        // Raggruppa per Proprietario (Plot)
        Map<UUID, List<PlacedMachine>> machinesByOwner = dirtyMachines.stream()
                .collect(Collectors.groupingBy(PlacedMachine::getOwnerId));

        System.out.println("💾 AutoSave: Saving dirty machines for " + machinesByOwner.size() + " plots.");

        for (Map.Entry<UUID, List<PlacedMachine>> entry : machinesByOwner.entrySet()) {
            List<PlacedMachine> toSave = entry.getValue();

            // Salva nel DB (aggiorna metadati)
            // ORA IL METODO ESISTE NELL'INTERFACCIA
            repository.updateMachinesMetadata(toSave);

            // Pulisce il flag dirty
            toSave.forEach(PlacedMachine::cleanDirty);
        }
    }
}