package com.matterworks.core.domain.factions;

public record FactionRotationSlot(
        int factionId,
        String factionName,
        long startEpochMs,
        long endEpochMs,
        long startsInMs,
        long endsInMs,
        boolean isCurrent
) {}
