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

    public DrillMachine(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata, int tierLevel) {
        super(dbId, ownerId, typeId, pos, metadata);

        this.tierLevel = tierLevel;
        this.productionSpeed = tierLevel * 1.0f;
        this.dimensions = new Vector3Int(1, 2, 1);

        int capacity = CoreConfig.getInt("machine.inventory.capacity", 64);
        this.outputBuffer = new MachineInventory(capacity);

        if (this.metadata != null && this.metadata.has("items")) {
            this.outputBuffer.loadState(this.metadata);
        }
    }

    @Override
    public void tick(long currentTick) {
        // 1. Produzione
        if (nextSpawnTick == -1) {
            nextSpawnTick = currentTick + (long)(20 / productionSpeed);
        }

        if (currentTick >= nextSpawnTick) {
            produceItem();
            nextSpawnTick = currentTick + (long)(20 / productionSpeed);
        }

        // 2. Output Logistico
        tryEjectItem(currentTick);
    }

    private void produceItem() {
        MatterPayload newItem = new MatterPayload(MatterShape.CUBE, MatterColor.RAW);
        if (outputBuffer.insert(newItem)) {
            System.out.println("Drill -> PRODUCED item. Buffer: " + outputBuffer.getCount());
            this.metadata = outputBuffer.serialize();
            markDirty();
        } else {
            // Buffer pieno
        }
    }

    private void tryEjectItem(long currentTick) {
        if (outputBuffer.isEmpty()) return;
        if (gridManager == null) return;

        // Trova il vicino in base all'orientamento
        Vector3Int dir = orientation.toVector();
        GridPosition targetPos = new GridPosition(pos.x() + dir.x(), pos.y() + dir.y(), pos.z() + dir.z());

        PlacedMachine neighbor = gridManager.getMachineAt(targetPos);

        if (neighbor instanceof ConveyorBelt belt) {
            MatterPayload item = outputBuffer.extractFirst();
            if (item != null) {
                boolean accepted = belt.insertItem(item, currentTick);
                if (accepted) {
                    System.out.println("Drill -> EJECTED item to Belt at " + targetPos);
                    this.metadata = outputBuffer.serialize();
                    markDirty();
                } else {
                    // Se rifiutato, rimetti in testa (rollback semplice)
                    // Nota: In un sistema reale useremmo peek() prima di extract()
                    outputBuffer.insert(item);
                }
            }
        }
    }
}