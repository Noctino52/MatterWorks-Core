package com.matterworks.core.domain.machines.production;

import com.google.gson.JsonObject;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.domain.inventory.MachineInventory;
import com.matterworks.core.domain.machines.base.PlacedMachine;
import com.matterworks.core.domain.machines.logistics.ConveyorBelt;
import com.matterworks.core.domain.matter.MatterColor;
import com.matterworks.core.domain.matter.MatterPayload;
import com.matterworks.core.domain.matter.MatterShape;

import java.util.UUID;

public class DrillMachine extends PlacedMachine {

    private final MachineInventory outputBuffer;

    private long nextSpawnTick = -1;

    private final int tierLevel;
    private final float productionSpeed;

    private MatterColor resourceToMine = MatterColor.RAW;

    public DrillMachine(Long dbId,
                        UUID ownerId,
                        GridPosition pos,
                        String typeId,
                        JsonObject metadata,
                        int tierLevel,
                        int maxStackPerSlot) {
        super(dbId, ownerId, typeId, pos, metadata);

        this.dimensions = Vector3Int.one();
        this.tierLevel = tierLevel;

        this.outputBuffer = new MachineInventory(1, maxStackPerSlot);

        if (this.metadata.has("items")) {
            this.outputBuffer.loadState(this.metadata);
        }

        if (this.metadata.has("mining_resource")) {
            this.resourceToMine = MatterColor.valueOf(this.metadata.get("mining_resource").getAsString());
        }

        this.productionSpeed = switch (tierLevel) {
            case 1 -> 1.0f;
            case 2 -> 2.0f;
            case 3 -> 4.0f;
            default -> 1.0f;
        };
    }

    public void setResourceToMine(MatterColor c) {
        if (c == null) return;
        this.resourceToMine = c;
        saveInternalState();
    }

    @Override
    public void tick(long currentTick) {
        long baseInterval = (long) (20 / productionSpeed);
        if (baseInterval < 1) baseInterval = 1;

        if (nextSpawnTick == -1) {
            nextSpawnTick = scheduleAfter(currentTick, baseInterval, "PROD_SPAWN");
        }

        if (currentTick >= nextSpawnTick) {
            produceItem();
            nextSpawnTick = scheduleAfter(currentTick, baseInterval, "PROD_SPAWN");
        }

        tryEjectItem(currentTick);
    }

    private void produceItem() {
        MatterShape shape = (this.resourceToMine == MatterColor.RAW) ? MatterShape.CUBE : null;
        MatterPayload newItem = new MatterPayload(shape, this.resourceToMine);

        if (outputBuffer.insert(newItem)) {
            // ✅ Telemetry: produced
            try {
                if (gridManager != null && gridManager.getProductionTelemetry() != null) {
                    gridManager.getProductionTelemetry().recordProduced(getOwnerId(), newItem, 1L);
                }
            } catch (Throwable ignored) {}

            saveInternalState();
        }
    }

    private void tryEjectItem(long currentTick) {
        if (outputBuffer.isEmpty()) return;

        Vector3Int dir = orientationToVector();
        GridPosition targetPos = new GridPosition(pos.x() + dir.x(), pos.y() + dir.y(), pos.z() + dir.z());

        PlacedMachine neighbor = getNeighborAt(targetPos);

        if (neighbor instanceof ConveyorBelt belt) {
            MatterPayload item = outputBuffer.extractFirst();
            if (item != null) {
                if (belt.insertItem(item, currentTick)) {
                    saveInternalState();
                } else {
                    outputBuffer.insert(item);
                }
            }
        }
    }

    private void saveInternalState() {
        JsonObject invState = outputBuffer.serialize();
        this.metadata.add("items", invState.get("items"));
        this.metadata.add("capacity", invState.get("capacity"));
        this.metadata.addProperty("mining_resource", resourceToMine.name());
        markDirty();
    }
}
