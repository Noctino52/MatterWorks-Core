package com.matterworks.core.domain.machines;

import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.database.dao.MachineDefinitionDAO;
import com.matterworks.core.ports.IWorldAccess;

import java.util.HashMap;
import java.util.Map;

public class BlockRegistry {

    private final IWorldAccess worldAdapter;
    private final MachineDefinitionDAO definitionDAO;

    // Cache aggiornata: String -> MachineStats
    private final Map<String, MachineStats> statsCache;

    public BlockRegistry(IWorldAccess worldAdapter, MachineDefinitionDAO definitionDAO) {
        this.worldAdapter = worldAdapter;
        this.definitionDAO = definitionDAO;
        this.statsCache = new HashMap<>();
    }

    public void loadFromDatabase() {
        System.out.println("📋 Caricamento prezzi e dimensioni dal DB...");
        Map<String, MachineStats> dbDefs = definitionDAO.loadAllDefinitions();

        dbDefs.forEach((id, stats) -> {
            statsCache.put(id, stats);
            System.out.println("   -> " + id + ": " + stats.dimensions() + " | $" + stats.basePrice());
        });
    }

    public MachineStats getStats(String blockId) {
        return statsCache.getOrDefault(blockId, MachineStats.fallback(blockId));
    }

    public Vector3Int getDimensions(String blockId) {
        if (statsCache.containsKey(blockId)) {
            return statsCache.get(blockId).dimensions();
        }
        // Fallback su Hytale (senza prezzo)
        Vector3Int dim = worldAdapter.fetchExternalBlockDimensions(blockId);
        statsCache.put(blockId, new MachineStats(dim, 0.0, blockId));
        return dim;
    }

    public double getPrice(String blockId) {
        if (statsCache.containsKey(blockId)) {
            return statsCache.get(blockId).basePrice();
        }
        return 0.0; // Gratis se non definito (o fallback)
    }
}