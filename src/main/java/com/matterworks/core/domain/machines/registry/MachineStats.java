package com.matterworks.core.domain.machines.registry;

import com.matterworks.core.common.Vector3Int;

public record MachineStats(
        String id,
        Vector3Int dimensions,
        double basePrice,
        double prestigeCostMult,
        int tier,
        String modelId,
        String category,
        double speed,
        int shopOrder
) {
    public static MachineStats fallback(String id) {
        return new MachineStats(
                id,
                Vector3Int.one(),
                0.0,
                0.0,
                1,
                "model_missing",
                "UNKNOWN",
                1.0,
                0
        );
    }
}
