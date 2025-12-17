package com.matterworks.core.domain.factory;

import com.matterworks.core.common.GridPosition;
import com.matterworks.core.domain.machines.*;
import com.matterworks.core.model.PlotObject;

import java.util.UUID;

public class MachineFactory {

    public static PlacedMachine createFromModel(PlotObject model, UUID ownerId) {
        GridPosition pos = new GridPosition(model.getX(), model.getY(), model.getZ());

        return switch (model.getTypeId()) {
            case "drill_mk1" -> new DrillMachine(
                    model.getId(),
                    ownerId,
                    pos,
                    "drill_mk1",
                    model.getMetaData(),
                    1
            );

            case "conveyor_belt" -> new ConveyorBelt(
                    model.getId(),
                    ownerId,
                    pos,
                    "conveyor_belt",
                    model.getMetaData()
            );

            // NEW: Creazione Nexus
            case "nexus_core" -> new NexusMachine(
                    model.getId(),
                    ownerId,
                    pos,
                    "nexus_core",
                    model.getMetaData()
            );
            case "chromator" -> new Chromator(
                    model.getId(),
                    ownerId,
                    pos,
                    "chromator",
                    model.getMetaData()
            );
            case "color_mixer" -> new ColorMixer(model.getId(), ownerId, pos, "color_mixer", model.getMetaData());


            default -> {
                System.err.println("Unknown machine type: " + model.getTypeId());
                yield null;
            }
        };
    }
}