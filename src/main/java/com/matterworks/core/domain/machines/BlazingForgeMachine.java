package com.matterworks.core.domain.machines;

import com.google.gson.JsonObject;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.domain.matter.MatterEffect;

import java.util.UUID;

/**
 * Applica l'effetto BLAZING.
 * ID consigliato: "blazing_forge"
 */
public class BlazingForgeMachine extends EffectApplicatorMachine {

    public BlazingForgeMachine(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata) {
        super(dbId, ownerId, pos, typeId, metadata, MatterEffect.BLAZING);
    }
}
