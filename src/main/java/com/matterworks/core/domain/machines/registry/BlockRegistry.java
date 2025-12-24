// FILE: src/main/java/com/matterworks/core/domain/machines/BlockRegistry.java
package com.matterworks.core.domain.machines.registry;

import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.database.dao.MachineDefinitionDAO;
import com.matterworks.core.ports.IWorldAccess;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class BlockRegistry {

    private final IWorldAccess worldAdapter;
    private final MachineDefinitionDAO definitionDAO;

    // cache stats caricati dal DB (machine_definitions JOIN item_definitions)
    private final Map<String, MachineStats> statsCache = new ConcurrentHashMap<>();

    public BlockRegistry(IWorldAccess worldAdapter, MachineDefinitionDAO definitionDAO) {
        this.worldAdapter = worldAdapter;
        this.definitionDAO = definitionDAO;
    }

    public void loadFromDatabase() {
        System.out.println("[BlockRegistry] Loading block definitions from DB...");
        Map<String, MachineStats> dbDefs = definitionDAO.loadAllDefinitions();
        statsCache.clear();
        statsCache.putAll(dbDefs);

        for (var e : dbDefs.entrySet()) {
            MachineStats s = e.getValue();
            System.out.println("  - " + e.getKey()
                    + " [tier " + s.tier() + "] "
                    + s.dimensions() + " price=$" + s.basePrice()
                    + " cat=" + s.category()
                    + " model=" + s.modelId());
        }
    }

    public MachineStats getStats(String blockId) {
        if (blockId == null) return MachineStats.fallback("null");
        return statsCache.getOrDefault(blockId, MachineStats.fallback(blockId));
    }

    /**
     * Per GUI/Shop: lista item MACHINE caricati dal DB, ordinati per tier e poi id.
     * Se il DB non ha definizioni, torna lista vuota.
     */
    public List<String> getShopMachineIdsFromDb() {
        return statsCache.values().stream()
                .filter(Objects::nonNull)
                .filter(s -> s.category() != null && s.category().equalsIgnoreCase("MACHINE"))
                .sorted(Comparator
                        .comparingInt(MachineStats::tier)
                        .thenComparing(MachineStats::id))
                .map(MachineStats::id)
                .distinct()
                .collect(Collectors.toList());
    }

    public Vector3Int getDimensions(String blockId) {
        if (blockId == null) return Vector3Int.one();

        MachineStats stats = statsCache.get(blockId);
        if (stats != null && stats.dimensions() != null) {
            return stats.dimensions();
        }

        // Hardcoded fallback per stabilità quando DB mancante / non aggiornato
        return switch (blockId) {
            case "nexus_core" -> new Vector3Int(3, 3, 3);

            case "chromator", "color_mixer", "splitter", "merger" -> new Vector3Int(2, 1, 1);

            // GDD / già esistenti
            case "smoothing", "cutting" -> new Vector3Int(2, 1, 1);

            // Nuovi: effetti su matter (shiny / blazing / glitch)
            case "shiny_polisher", "blazing_forge", "glitch_distorter" -> new Vector3Int(2, 1, 1);

            // Componenti base
            case "conveyor_belt" -> Vector3Int.one();
            case "drill_mk1" -> new Vector3Int(1, 2, 1);
            case "lift", "dropper" -> new Vector3Int(1, 2, 1);

            default -> Vector3Int.one();
        };
    }

    public double getPrice(String blockId) {
        if (blockId == null) return 0.0;
        MachineStats s = statsCache.get(blockId);
        return (s != null ? s.basePrice() : 0.0);
    }

    public String getModelId(String blockId) {
        if (blockId == null) return "model_missing";
        MachineStats s = statsCache.get(blockId);
        return (s != null && s.modelId() != null ? s.modelId() : "model_missing");
    }

    // Se in futuro ti serve, l’adapter resta qui (non lo rimuovo)
    public IWorldAccess getWorldAdapter() {
        return worldAdapter;
    }
}
