package com.matterworks.core.domain.factions;

public record FactionRotationInfo(
        boolean enabled,
        int rotationHours,
        int currentFactionId,
        String currentFactionName,
        int nextFactionId,
        String nextFactionName,
        long remainingMs,
        long nextChangeEpochMs
) {

    public static FactionRotationInfo disabled(int currentFactionId, String currentFactionName) {
        return new FactionRotationInfo(
                false,
                0,
                currentFactionId,
                currentFactionName != null ? currentFactionName : ("Faction #" + currentFactionId),
                currentFactionId,
                currentFactionName != null ? currentFactionName : ("Faction #" + currentFactionId),
                -1L,
                -1L
        );
    }
}
