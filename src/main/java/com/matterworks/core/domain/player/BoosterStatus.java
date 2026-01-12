package com.matterworks.core.domain.player;

public record BoosterStatus(
        String boosterId,
        String displayName,
        double multiplier,
        long remainingSeconds, // -1 = lifetime
        long totalDurationSeconds // -1 = lifetime
) {}
