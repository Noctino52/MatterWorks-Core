package com.matterworks.core.domain.machines;

import com.google.gson.JsonObject;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;

import java.util.UUID;

public class Chromator extends ProcessorMachine {

    public Chromator(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata) {
        super(dbId, ownerId, pos, typeId, metadata);
        // Il Chromator occupa 1x1x1
        this.dimensions = new Vector3Int(1, 1, 1);
    }
}