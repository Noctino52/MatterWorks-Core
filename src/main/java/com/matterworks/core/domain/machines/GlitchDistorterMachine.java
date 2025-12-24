package com.matterworks.core.domain.machines;

import com.google.gson.JsonObject;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.domain.matter.MatterEffect;

import java.util.UUID;

/**
 * Applica l'effetto GLITCH.
 * ID consigliato: "glitch_distorter"
 */
public class GlitchDistorterMachine extends EffectApplicatorMachine {

    public GlitchDistorterMachine(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata) {
        super(dbId, ownerId, pos, typeId, metadata, MatterEffect.GLITCH);
    }
}
