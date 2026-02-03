package com.matterworks.core.domain.machines.base;

import com.google.gson.JsonObject;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.domain.inventory.MachineInventory;
import com.matterworks.core.domain.machines.logistics.ConveyorBelt;
import com.matterworks.core.domain.machines.production.NexusMachine;
import com.matterworks.core.domain.matter.MatterPayload;
import com.matterworks.core.domain.matter.Recipe;

import java.util.UUID;

/**
 * Processor base machine.
 *
 * PERFORMANCE:
 * - Hot path MUST be allocation-light.
 * - Subclasses can use the "pendingOutput" mode to avoid allocating Recipe/List every job.
 *
 * Compatibility:
 * - Keeps currentRecipe field and the old flow working for machines not yet migrated.
 * - Keeps saveState() so subclasses that call it still compile.
 */
public abstract class ProcessorMachine extends PlacedMachine {

    public final MachineInventory inputBuffer;
    public final MachineInventory outputBuffer;

    // Legacy flow (still supported)
    public Recipe currentRecipe;

    // New flow (no Recipe allocation)
    protected MatterPayload pendingOutput;

    protected long finishTick = -1;

    protected static final int MAX_INPUT_STACK = 64;
    public static final int MAX_OUTPUT_STACK = 64;

    protected final int maxStackPerSlot;

    // If output is blocked, do not attempt ejection every tick
    private long nextEjectAttemptTick = 0;

    // Runtime dirty flag (avoid JSON churn in hot path)
    private boolean runtimeStateDirty = false;

    public ProcessorMachine(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata) {
        this(dbId, ownerId, pos, typeId, metadata, 64);
    }

    public ProcessorMachine(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata, int maxStackPerSlot) {
        super(dbId, ownerId, typeId, pos, metadata);

        this.maxStackPerSlot = Math.max(1, maxStackPerSlot);

        this.inputBuffer = new MachineInventory(2, this.maxStackPerSlot);
        this.outputBuffer = new MachineInventory(1, this.maxStackPerSlot);

        // Load persisted state once (if present)
        try {
            if (this.metadata != null && this.metadata.has("input") && this.metadata.get("input").isJsonObject()) {
                this.inputBuffer.loadState(this.metadata.getAsJsonObject("input"));
            }
            if (this.metadata != null && this.metadata.has("output") && this.metadata.get("output").isJsonObject()) {
                this.outputBuffer.loadState(this.metadata.getAsJsonObject("output"));
            }
        } catch (Throwable ignored) {
        }
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

    protected void consumeInput(int slotIndex, int amount, MatterPayload consumedItem) {
        if (amount <= 0) return;

        inputBuffer.decreaseSlot(slotIndex, amount);

        try {
            if (gridManager != null && gridManager.getProductionTelemetry() != null && consumedItem != null) {
                gridManager.getProductionTelemetry().recordConsumed(getOwnerId(), consumedItem, amount);
            }
        } catch (Throwable ignored) {}

        saveState();
    }

    /**
     * Starts a job without allocating a Recipe.
     */
    protected final void startProcessing(MatterPayload output, long currentTick, long processTicks, String reason) {
        if (output == null) return;
        this.pendingOutput = output;
        this.currentRecipe = null; // ensure we are in the new flow
        this.finishTick = scheduleAfter(currentTick, processTicks, reason);
        saveState();
    }

    /**
     * Returns true if this machine is currently processing something.
     */
    protected final boolean isProcessing() {
        return pendingOutput != null || currentRecipe != null;
    }

    /**
     * Completes either the new-flow (pendingOutput) or the legacy-flow (currentRecipe.output()).
     */
    protected void completeProcessing() {
        MatterPayload out = null;

        if (pendingOutput != null) out = pendingOutput;
        else if (currentRecipe != null) out = currentRecipe.output();

        if (out == null) return;

        if (outputBuffer.insert(out)) {
            try {
                if (gridManager != null && gridManager.getProductionTelemetry() != null) {
                    gridManager.getProductionTelemetry().recordProduced(getOwnerId(), out, 1L);
                }
            } catch (Throwable ignored) {}

            // Clear state
            pendingOutput = null;
            currentRecipe = null;
            finishTick = -1;

            saveState();
        }
    }

    /**
     * Output ejection:
     * - If a job is finished, materialize its output BEFORE attempting ejection,
     *   so processors can reach full MK1 throughput (no +1 tick penalty).
     * - Backoff when blocked, but do not introduce a systematic gap.
     */
    protected void tryEjectItem(long currentTick) {
        if (gridManager == null) return;

        // IMPORTANT: if processing is finished, move output into the buffer NOW.
        // This allows eject in the same tick and avoids the classic 20/(20+1) throughput loss.
        if (outputBuffer.isEmpty() && isProcessing() && finishTick != -1 && currentTick >= finishTick) {
            completeProcessing(); // may fill outputBuffer and clear processing state
        }

        if (outputBuffer.isEmpty()) return;
        if (currentTick < nextEjectAttemptTick) return;

        GridPosition targetPos = getOutputPosition();
        PlacedMachine neighbor = getNeighborAt(targetPos);

        if (neighbor == null) {
            // Retry next tick (belt/drill philosophy: avoid systematic throughput loss due to tick ordering)
            nextEjectAttemptTick = currentTick + 1;
            return;
        }

        if (neighbor instanceof ConveyorBelt belt) {
            MatterPayload item = outputBuffer.extractFirst();
            if (item == null) return;

            boolean moved;
            try {
                moved = belt.insertItem(item, currentTick);
            } catch (Throwable ignored) {
                moved = false;
            }

            if (moved) {
                nextEjectAttemptTick = 0;
                saveState();
            } else {
                outputBuffer.insert(item);
                // Retry next tick (no 2-tick backoff)
                nextEjectAttemptTick = currentTick + 1;
            }
            return;
        }

        if (neighbor instanceof NexusMachine nexus) {
            MatterPayload item = outputBuffer.extractFirst();
            if (item == null) return;

            boolean moved;
            try {
                moved = nexus.insertItem(item, this.pos);
            } catch (Throwable ignored) {
                moved = false;
            }

            if (moved) {
                nextEjectAttemptTick = 0;
                saveState();
            } else {
                outputBuffer.insert(item);
                nextEjectAttemptTick = currentTick + 1;
            }
            return;
        }

        // Other machines: small backoff is fine
        nextEjectAttemptTick = currentTick + 2;
    }



    /**
     * Compatibility method: subclasses call saveState().
     * New behavior: no buffer serialization here. We only mark runtime dirty + DB dirty.
     */
    protected void saveState() {
        runtimeStateDirty = true;
        markDirty();
    }

    @Override
    public JsonObject serialize() {
        // Materialize buffers ONLY here
        if (this.metadata == null) this.metadata = new JsonObject();

        if (runtimeStateDirty) {
            this.metadata.add("input", inputBuffer.serialize());
            this.metadata.add("output", outputBuffer.serialize());
            runtimeStateDirty = false;
        } else {
            if (!this.metadata.has("input")) this.metadata.add("input", inputBuffer.serialize());
            if (!this.metadata.has("output")) this.metadata.add("output", outputBuffer.serialize());
        }

        return super.serialize();
    }

    public int getMaxStackPerSlot() {
        return maxStackPerSlot;
    }
}
