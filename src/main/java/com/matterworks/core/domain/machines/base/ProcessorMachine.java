package com.matterworks.core.domain.machines.base;

import com.google.gson.JsonObject;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.domain.inventory.MachineInventory;
import com.matterworks.core.domain.machines.logistics.ConveyorBelt;
import com.matterworks.core.domain.machines.production.NexusMachine;
import com.matterworks.core.domain.matter.MatterPayload;
import com.matterworks.core.domain.matter.Recipe;

import java.util.UUID;

public abstract class ProcessorMachine extends PlacedMachine {

    public final MachineInventory inputBuffer;
    public final MachineInventory outputBuffer;

    public Recipe currentRecipe;
    protected long finishTick = -1;

    /** default legacy */
    protected static final int MAX_INPUT_STACK = 64;
    /** default legacy */
    public static final int MAX_OUTPUT_STACK = 64;

    protected final int maxStackPerSlot;

    /** Legacy constructor (default 64). */
    public ProcessorMachine(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata) {
        this(dbId, ownerId, pos, typeId, metadata, 64);
    }

    /** New constructor with configurable max stack per slot. */
    public ProcessorMachine(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata, int maxStackPerSlot) {
        super(dbId, ownerId, typeId, pos, metadata);

        this.maxStackPerSlot = Math.max(1, maxStackPerSlot);

        this.inputBuffer = new MachineInventory(2, this.maxStackPerSlot);
        this.outputBuffer = new MachineInventory(1, this.maxStackPerSlot);

        if (this.metadata.has("input")) this.inputBuffer.loadState(this.metadata.getAsJsonObject("input"));
        if (this.metadata.has("output")) this.outputBuffer.loadState(this.metadata.getAsJsonObject("output"));
    }

    protected abstract GridPosition getOutputPosition();
    public abstract boolean insertItem(MatterPayload item, GridPosition fromPos);

    protected boolean insertIntoBuffer(int slotIndex, MatterPayload item) {
        if (item == null) return false;

        if (inputBuffer.getCountInSlot(slotIndex) >= inputBuffer.getMaxStackSize()) return false;

        if (inputBuffer.insertIntoSlot(slotIndex, item)) {
            saveState();
            return true;
        }
        return false;
    }

    protected void completeProcessing() {
        if (currentRecipe != null && outputBuffer.insert(currentRecipe.output())) {
            this.currentRecipe = null;
            this.finishTick = -1;
            saveState();
        }
    }

    protected void tryEjectItem(long currentTick) {
        if (outputBuffer.isEmpty() || gridManager == null) return;

        GridPosition targetPos = getOutputPosition();
        PlacedMachine neighbor = getNeighborAt(targetPos);

        metadata.addProperty("ejectTarget", String.valueOf(targetPos));
        metadata.addProperty("ejectNeighbor", neighbor == null ? "null" : neighbor.getClass().getSimpleName() + "/" + neighbor.getTypeId());

        if (neighbor instanceof ConveyorBelt belt) {
            MatterPayload item = outputBuffer.extractFirst();
            if (item != null) {
                if (belt.insertItem(item, currentTick)) {
                    metadata.addProperty("ejectResult", "OK->BELT");
                    saveState();
                } else {
                    outputBuffer.insert(item);
                    metadata.addProperty("ejectResult", "BELT_FULL");
                    saveState();
                }
            }
        } else if (neighbor instanceof NexusMachine nexus) {
            MatterPayload item = outputBuffer.extractFirst();
            if (item != null) {
                if (nexus.insertItem(item, this.pos)) {
                    metadata.addProperty("ejectResult", "OK->NEXUS");
                    saveState();
                } else {
                    outputBuffer.insert(item);
                    metadata.addProperty("ejectResult", "NEXUS_REJECT");
                    saveState();
                }
            }
        } else {
            metadata.addProperty("ejectResult", "NO_VALID_TARGET");
            markDirty();
        }
    }

    protected void saveState() {
        this.metadata.add("input", inputBuffer.serialize());
        this.metadata.add("output", outputBuffer.serialize());
        markDirty();
    }

    public int getMaxStackPerSlot() {
        return maxStackPerSlot;
    }
}
