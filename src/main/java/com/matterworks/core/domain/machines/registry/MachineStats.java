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

        // Dynamic price penalty (owned count based)
        int pricePenaltyEvery,
        double pricePenaltyAdd,

        // Processing ticks per tier (DB-driven)
        long mk1ProcessTicks,
        long mk2ProcessTicks,
        long mk3ProcessTicks,

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
                0,
                0.0,
                20L, // default MK1: 20 ticks
                20L, // default MK2: 20 ticks
                20L, // default MK3: 20 ticks
                0
        );
    }

    public long getProcessTicksForTier(int tier, long fallback) {
        int t = Math.max(1, Math.min(3, tier));
        long v = switch (t) {
            case 2 -> mk2ProcessTicks;
            case 3 -> mk3ProcessTicks;
            default -> mk1ProcessTicks;
        };
        if (v <= 0) return Math.max(1L, fallback);
        return v;
    }
}
