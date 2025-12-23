package com.matterworks.core.domain.factory;

import com.google.gson.JsonObject;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.domain.machines.*;
import com.matterworks.core.model.PlotObject;

import java.util.UUID;

public class MachineFactory {

    public static PlacedMachine createFromModel(PlotObject model, UUID ownerId) {
        GridPosition pos = new GridPosition(model.getX(), model.getY(), model.getZ());
        JsonObject metadata = model.getMetaData();
        if (metadata == null) metadata = new JsonObject();

        Long dbId = model.getId();
        String typeId = model.getTypeId();

        return switch (typeId) {
            case "drill_mk1" -> new DrillMachine(dbId, ownerId, pos, typeId, metadata, 1);
            case "conveyor_belt" -> new ConveyorBelt(dbId, ownerId, pos, typeId, metadata);
            case "nexus_core" -> new NexusMachine(dbId, ownerId, pos, typeId, metadata);
            case "chromator" -> new Chromator(dbId, ownerId, pos, typeId, metadata);
            case "color_mixer" -> new ColorMixer(dbId, ownerId, pos, typeId, metadata);
            case "splitter" -> new Splitter(dbId, ownerId, pos, typeId, metadata);
            case "merger" -> new Merger(dbId, ownerId, pos, typeId, metadata);
            case "lift" -> new LiftMachine(dbId, ownerId, pos, typeId, metadata);
            case "dropper" -> new DropperMachine(dbId, ownerId, pos, typeId, metadata);

            // ✅ NUOVE MACCHINE
            case "smoothing" -> new ShaperMachine(dbId, ownerId, pos, typeId, metadata);
            case "cutting" -> new CuttingMachine(dbId, ownerId, pos, typeId, metadata);

            // strutture
            case "STRUCTURE_GENERIC" -> new StructuralBlock(dbId, ownerId, pos, typeId, metadata);

            default -> null;
        };
    }
}
