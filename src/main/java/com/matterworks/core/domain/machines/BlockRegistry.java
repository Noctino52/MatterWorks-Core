package com.matterworks.core.domain.machines;

import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.database.dao.MachineDefinitionDAO;
import com.matterworks.core.ports.IWorldAccess;
import java.util.HashMap;
import java.util.Map;

public class BlockRegistry {

    private final IWorldAccess worldAdapter;
    private final MachineDefinitionDAO definitionDAO;
    private final Map<String, MachineStats> statsCache;

    public BlockRegistry(IWorldAccess worldAdapter, MachineDefinitionDAO definitionDAO) {
        this.worldAdapter = worldAdapter;
        this.definitionDAO = definitionDAO;
        this.statsCache = new HashMap<>();
    }

    public void loadFromDatabase() {
        System.out.println("📋 Caricamento definizioni blocchi dal DB...");
        Map<String, MachineStats> dbDefs = definitionDAO.loadAllDefinitions();
        statsCache.putAll(dbDefs);

        dbDefs.forEach((id, stats) ->
                System.out.println("   -> " + id + " [Tier " + stats.tier() + "]: "
                        + stats.dimensions() + " | $" + stats.basePrice())
        );
    }

    public MachineStats getStats(String blockId) {
        return statsCache.getOrDefault(blockId, MachineStats.fallback(blockId));
    }

    public Vector3Int getDimensions(String blockId) {
        if (statsCache.containsKey(blockId)) {
            return statsCache.get(blockId).dimensions();
        }

        // Hardcoded Fallback per stabilità in caso di DB mancante
        return switch (blockId) {
            case "nexus_core" -> new Vector3Int(3, 3, 3);
            case "chromator", "color_mixer", "splitter", "merger" -> new Vector3Int(2, 1, 1); // Merger aggiunto qui
            case "drill_mk1" -> new Vector3Int(1, 2, 1);
            default -> Vector3Int.one();
        };
    }

    public double getPrice(String blockId) {
        if (statsCache.containsKey(blockId)) {
            return statsCache.get(blockId).basePrice();
        }
        return 0.0;
    }

    public String getModelId(String blockId) {
        if (statsCache.containsKey(blockId)) {
            return statsCache.get(blockId).modelId();
        }
        return "model_missing";
    }
}