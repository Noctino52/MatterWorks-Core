package com.matterworks.core.synchronization;

import com.matterworks.core.common.GridPosition;
import com.matterworks.core.domain.machines.PlacedMachine;
import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.managers.GridManager;
import com.matterworks.core.ports.IRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class GridSaverService {

    private final GridManager gridManager;
    private final IRepository repository;

    public GridSaverService(GridManager gridManager, IRepository repository) {
        this.gridManager = gridManager;
        this.repository = repository;
    }

    public void autoSaveTask() {
        List<PlayerProfile> players;
        try {
            players = repository.getAllPlayers();
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        int plotsTouched = 0;
        int plotsFailed = 0;

        for (PlayerProfile p : players) {
            UUID ownerId = p.getPlayerId();

            Map<GridPosition, PlacedMachine> snapshot = gridManager.getSnapshot(ownerId);
            if (snapshot.isEmpty()) continue;

            List<PlacedMachine> dirty = new ArrayList<>();
            for (PlacedMachine m : snapshot.values()) {
                if (m != null && m.isDirty() && m.getDbId() != null) {
                    dirty.add(m);
                }
            }

            if (dirty.isEmpty()) continue;

            try {
                repository.updateMachinesMetadata(dirty);
                dirty.forEach(PlacedMachine::cleanDirty);
                plotsTouched++;
            } catch (Exception ex) {
                plotsFailed++;
                System.err.println("🚨 AutoSave failed for plot owner " + ownerId + " (dirty kept, will retry).");
                ex.printStackTrace();
            }
        }

        if (plotsTouched > 0 || plotsFailed > 0) {
            System.out.println("💾 AutoSave: saved plots=" + plotsTouched + ", failed=" + plotsFailed);
        }
    }
}
