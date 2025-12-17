package com.matterworks.core.synchronization;

import com.matterworks.core.common.GridPosition;
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
        // FIX: Usa getAllMachinesSnapshot() invece di getSnapshot()
        // Questo recupera le macchine di TUTTI i giocatori per salvarle.
        Map<GridPosition, PlacedMachine> allMachines = gridManager.getAllMachinesSnapshot();

        List<PlacedMachine> dirtyMachines = allMachines.values().stream()
                .filter(PlacedMachine::isDirty)
                .distinct()
                .collect(Collectors.toList());

        if (dirtyMachines.isEmpty()) return;

        Map<UUID, List<PlacedMachine>> machinesByOwner = dirtyMachines.stream()
                .collect(Collectors.groupingBy(PlacedMachine::getOwnerId));

        System.out.println("💾 AutoSave: Saving dirty machines for " + machinesByOwner.size() + " plots.");

        for (Map.Entry<UUID, List<PlacedMachine>> entry : machinesByOwner.entrySet()) {
            List<PlacedMachine> toSave = entry.getValue();
            repository.updateMachinesMetadata(toSave);
            toSave.forEach(PlacedMachine::cleanDirty);
        }
    }
}