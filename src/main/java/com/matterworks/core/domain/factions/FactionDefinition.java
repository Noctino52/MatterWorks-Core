package com.matterworks.core.domain.factions;

public record FactionDefinition(
        int id,
        String code,
        String displayName,
        String description,
        int sortOrder
) {}
