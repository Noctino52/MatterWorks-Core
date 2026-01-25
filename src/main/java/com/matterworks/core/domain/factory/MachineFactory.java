package com.matterworks.core.domain.factory;

import com.google.gson.JsonObject;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.domain.machines.base.PlacedMachine;
import com.matterworks.core.domain.machines.logistics.*;
import com.matterworks.core.domain.machines.processing.*;
import com.matterworks.core.domain.machines.production.DrillMachine;
import com.matterworks.core.domain.machines.production.NexusMachine;
import com.matterworks.core.domain.machines.structure.StructuralBlock;
import com.matterworks.core.model.PlotObject;
import com.matterworks.core.ui.ServerConfig;

import java.util.UUID;

public class MachineFactory {

    public static PlacedMachine createFromModel(PlotObject model, UUID ownerId) {
        return createFromModel(model, ownerId, null);
    }

    public static PlacedMachine createFromModel(PlotObject model, UUID ownerId, ServerConfig serverConfig) {
        GridPosition pos = new GridPosition(model.getX(), model.getY(), model.getZ());
        JsonObject metadata = model.getMetaData();
        if (metadata == null) metadata = new JsonObject();

        Long dbId = model.getId();
        String typeId = normalizeTypeId(model.getTypeId());

        int maxStack = (serverConfig != null) ? Math.max(1, serverConfig.maxInventoryMachine()) : 64;

        return switch (typeId) {
            // non-logistics -> use maxStack from config
            case "drill" -> new DrillMachine(dbId, ownerId, pos, typeId, metadata, maxStack);

            case "chromator" -> new Chromator(dbId, ownerId, pos, typeId, metadata, maxStack);
            case "color_mixer" -> new ColorMixer(dbId, ownerId, pos, typeId, metadata, maxStack);
            case "smoothing" -> new ShaperMachine(dbId, ownerId, pos, typeId, metadata, maxStack);
            case "cutting" -> new CuttingMachine(dbId, ownerId, pos, typeId, metadata, maxStack);

            case "shiny_polisher" -> new ShinyPolisherMachine(dbId, ownerId, pos, typeId, metadata, maxStack);
            case "blazing_forge" -> new BlazingForgeMachine(dbId, ownerId, pos, typeId, metadata, maxStack);
            case "glitch_distorter" -> new GlitchDistorterMachine(dbId, ownerId, pos, typeId, metadata, maxStack);

            // logistics
            case "conveyor_belt" -> new ConveyorBelt(dbId, ownerId, pos, typeId, metadata);
            case "splitter" -> new Splitter(dbId, ownerId, pos, typeId, metadata);
            case "merger" -> new Merger(dbId, ownerId, pos, typeId, metadata);
            case "lift" -> new LiftMachine(dbId, ownerId, pos, typeId, metadata);
            case "dropper" -> new DropperMachine(dbId, ownerId, pos, typeId, metadata);

            // nexus
            case "nexus_core" -> new NexusMachine(dbId, ownerId, pos, typeId, metadata);

            // structures
            case "STRUCTURE_GENERIC" -> new StructuralBlock(dbId, ownerId, pos, typeId, metadata);

            default -> null;
        };
    }

    private static String normalizeTypeId(String typeId) {
        if (typeId == null) return null;
        if ("drill".equals(typeId)) return "drill";
        return typeId;
    }
}
