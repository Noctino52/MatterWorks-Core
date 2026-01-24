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
                    + s.dimensions()
                    + " price=$" + s.basePrice()
                    + " pm=" + s.prestigeCostMult()
                    + " cat=" + s.category()
                    + " model=" + s.modelId()
                    + " speed=" + s.speed()
                    + " shopOrder=" + s.shopOrder());
        }
    }

    public MachineStats getStats(String blockId) {
        String id = normalizeId(blockId);
        if (id == null) return MachineStats.fallback("null");

        MachineStats s = statsCache.get(id);
        if (s != null) return s;

        if ("drill".equals(blockId)) {
            MachineStats drill = statsCache.get("drill");
            if (drill != null) return drill;
        }

        return MachineStats.fallback(id);
    }

    public List<String> getShopMachineIdsFromDb() {
        return statsCache.values().stream()
                .filter(Objects::nonNull)
                .filter(s -> s.category() != null && s.category().equalsIgnoreCase("MACHINE"))
                .sorted(Comparator
                        .comparingInt(BlockRegistry::shopSortKey)
                        .thenComparingInt(MachineStats::tier)
                        .thenComparing(MachineStats::id))
                .map(MachineStats::id)
                .distinct()
                .collect(Collectors.toList());
    }

    private static int shopSortKey(MachineStats s) {
        if (s == null) return Integer.MAX_VALUE;

        int order = s.shopOrder();
        if (order > 0) return order;

        // Fallback: preserve old behavior when shop_order is missing/not set
        int tier = Math.max(0, s.tier());
        return 1_000_000 + (tier * 1000);
    }

    public Vector3Int getDimensions(String blockId) {
        String id = normalizeId(blockId);
        if (id == null) return Vector3Int.one();

        MachineStats stats = statsCache.get(id);
        if (stats != null && stats.dimensions() != null) return stats.dimensions();

        return switch (id) {
            case "nexus_core" -> new Vector3Int(3, 3, 3);
            case "chromator", "color_mixer", "splitter", "merger" -> new Vector3Int(2, 1, 1);
            case "smoothing", "cutting" -> new Vector3Int(2, 1, 1);
            case "shiny_polisher", "blazing_forge", "glitch_distorter" -> new Vector3Int(2, 1, 1);
            case "conveyor_belt" -> Vector3Int.one();
            case "drill" -> new Vector3Int(1, 2, 1);
            case "lift", "dropper" -> new Vector3Int(1, 2, 1);
            default -> Vector3Int.one();
        };
    }

    public double getPrice(String blockId) {
        String id = normalizeId(blockId);
        if (id == null) return 0.0;
        MachineStats s = statsCache.get(id);
        return (s != null ? s.basePrice() : 0.0);
    }

    public double getPrestigeCostMult(String blockId) {
        String id = normalizeId(blockId);
        if (id == null) return 0.0;
        MachineStats s = statsCache.get(id);
        return (s != null ? s.prestigeCostMult() : 0.0);
    }

    public double getSpeed(String blockId) {
        String id = normalizeId(blockId);
        if (id == null) return 1.0;
        MachineStats s = statsCache.get(id);
        double out = (s != null ? s.speed() : 1.0);
        if (Double.isNaN(out) || Double.isInfinite(out) || out <= 0.0) return 1.0;
        return out;
    }

    private String normalizeId(String id) {
        if (id == null) return null;
        if ("drill".equals(id)) return "drill";
        return id;
    }
}
