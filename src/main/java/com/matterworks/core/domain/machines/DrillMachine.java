package com.matterworks.core.domain.machines;

import com.google.gson.JsonObject;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.domain.inventory.MachineInventory;
import com.matterworks.core.domain.matter.MatterColor;
import com.matterworks.core.domain.matter.MatterPayload;
import com.matterworks.core.domain.matter.MatterShape;
import com.matterworks.core.infrastructure.CoreConfig;

import java.util.UUID;

public class DrillMachine extends PlacedMachine {

    private int tierLevel;
    private float productionSpeed;
    private long nextSpawnTick = -1;
    private final MachineInventory outputBuffer;

    private MatterColor resourceToMine = MatterColor.RAW;

    public DrillMachine(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata, int tierLevel) {
        super(dbId, ownerId, typeId, pos, metadata);
        this.tierLevel = tierLevel;
        this.productionSpeed = tierLevel * 1.0f;
        this.dimensions = new Vector3Int(1, 2, 1);

        int capacity = CoreConfig.getInt("machine.inventory.capacity", 64);
        this.outputBuffer = new MachineInventory(capacity);

        if (this.metadata != null) {
            if (this.metadata.has("items")) {
                this.outputBuffer.loadState(this.metadata);
            }
            if (this.metadata.has("mining_resource")) {
                try {
                    this.resourceToMine = MatterColor.valueOf(this.metadata.get("mining_resource").getAsString());
                } catch (Exception e) {
                    this.resourceToMine = MatterColor.RAW;
                }
            }
        }
    }

    public void setResourceToMine(MatterColor resource) {
        this.resourceToMine = resource;
        this.metadata.addProperty("mining_resource", resource.name());
        markDirty();
    }

    @Override
    public void tick(long currentTick) {
        if (nextSpawnTick == -1) {
            nextSpawnTick = currentTick + (long)(20 / productionSpeed);
        }

        if (currentTick >= nextSpawnTick) {
            produceItem();
            nextSpawnTick = currentTick + (long)(20 / productionSpeed);
        }
        tryEjectItem(currentTick);
    }

    private void produceItem() {
        // MODIFICA: Se è RAW è un CUBO, altrimenti è NULL (Liquido/Colore puro)
        MatterShape shape = (this.resourceToMine == MatterColor.RAW) ? MatterShape.CUBE : null;

        MatterPayload newItem = new MatterPayload(shape, this.resourceToMine);

        if (outputBuffer.insert(newItem)) {
            // System.out.println("Drill -> MINED " + this.resourceToMine + ". Buffer: " + outputBuffer.getCount());
            this.metadata = outputBuffer.serialize();
            this.metadata.addProperty("mining_resource", resourceToMine.name());
            markDirty();
        }
    }

    private void tryEjectItem(long currentTick) {
        if (outputBuffer.isEmpty()) return;
        if (gridManager == null) return;

        Vector3Int dir = orientation.toVector();
        GridPosition targetPos = new GridPosition(pos.x() + dir.x(), pos.y() + dir.y(), pos.z() + dir.z());

        PlacedMachine neighbor = gridManager.getMachineAt(targetPos);
        if (neighbor instanceof ConveyorBelt belt) {
            MatterPayload item = outputBuffer.extractFirst();
            if (item != null) {
                if (belt.insertItem(item, currentTick)) {
                    this.metadata = outputBuffer.serialize();
                    markDirty();
                } else {
                    outputBuffer.insert(item);
                }
            }
        }
    }
}