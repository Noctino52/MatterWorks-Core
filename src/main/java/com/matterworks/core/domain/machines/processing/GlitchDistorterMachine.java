package com.matterworks.core.domain.machines.processing;

import com.google.gson.JsonObject;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.domain.matter.MatterEffect;

import java.util.UUID;

public class GlitchDistorterMachine extends EffectApplicatorMachine {

    public GlitchDistorterMachine(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata) {
        this(dbId, ownerId, pos, typeId, metadata, 64);
    }

    public GlitchDistorterMachine(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata, int maxStackPerSlot) {
        super(dbId, ownerId, pos, typeId, metadata, MatterEffect.GLITCH, maxStackPerSlot);
    }
}
