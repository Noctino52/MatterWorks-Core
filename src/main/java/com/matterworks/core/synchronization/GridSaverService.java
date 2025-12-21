package com.matterworks.core.synchronization;

import com.matterworks.core.common.GridPosition;
import com.matterworks.core.domain.machines.PlacedMachine;
import com.matterworks.core.managers.GridManager;
import com.matterworks.core.ports.IRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

public class GridSaverService {

    private final GridManager gridManager;
    private final IRepository repository;
    private final ReentrantLock saveLock = new ReentrantLock();

    public GridSaverService(GridManager gridManager, IRepository repository) {
        this.gridManager = gridManager;
        this.repository = repository;
    }

    public void autoSaveTask() {
        if (!saveLock.tryLock()) {
            return;
        }

        try {
            Map<GridPosition, PlacedMachine> allMachines = gridManager.getAllMachinesSnapshot();

            List<PlacedMachine> dirtyMachines = allMachines.values().stream()
                    .filter(pm -> pm != null && pm.isDirty())
                    .distinct()
                    .collect(Collectors.toList());

            if (dirtyMachines.isEmpty()) return;

            Map<UUID, List<PlacedMachine>> machinesByOwner = dirtyMachines.stream()
                    .filter(pm -> pm.getOwnerId() != null)
                    .collect(Collectors.groupingBy(PlacedMachine::getOwnerId));

            if (machinesByOwner.isEmpty()) return;

            System.out.println("💾 AutoSave: Saving dirty machines for " + machinesByOwner.size() + " plots.");

            for (Map.Entry<UUID, List<PlacedMachine>> entry : machinesByOwner.entrySet()) {
                UUID ownerId = entry.getKey();
                List<PlacedMachine> toSave = entry.getValue();

                if (toSave == null || toSave.isEmpty()) continue;

                try {
                    repository.updateMachinesMetadata(toSave);
                    toSave.forEach(PlacedMachine::cleanDirty);
                } catch (RuntimeException ex) {
                    System.err.println("🚨 AutoSave failed for plot owner " + ownerId + " (dirty kept, will retry).");
                    ex.printStackTrace();
                }
            }
        } finally {
            saveLock.unlock();
        }
    }
}
