package com.matterworks.core.domain.machines.processing;

import com.google.gson.JsonObject;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.domain.matter.MatterEffect;

import java.util.UUID;

/**
 * Applica l'effetto SHINY.
 * ID consigliato: "shiny_polisher"
 */
public class ShinyPolisherMachine extends EffectApplicatorMachine {

    public ShinyPolisherMachine(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata) {
        super(dbId, ownerId, pos, typeId, metadata, MatterEffect.SHINY);
    }
}
