package com.matterworks.core.domain.matter;

import java.util.List;

public record Recipe(
        String id,
        List<MatterPayload> inputs, // Cosa serve (es. 1 Cubo RAW)
        MatterPayload output,       // Cosa esce (es. 1 Cubo ROSSO)
        float processTimeSeconds,   // Quanto ci mette (es. 2.0s)
        int requiredTechId          // Per sblocchi futuri (0 = base)
) {}
