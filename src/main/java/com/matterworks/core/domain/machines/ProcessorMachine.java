package com.matterworks.core.domain.machines;

import com.google.gson.JsonObject;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.domain.inventory.MachineInventory;
import com.matterworks.core.domain.matter.MatterPayload;
import com.matterworks.core.domain.matter.Recipe;

import java.util.UUID;

public abstract class ProcessorMachine extends PlacedMachine {

    protected final MachineInventory inputBuffer;
    protected final MachineInventory outputBuffer;
    protected Recipe currentRecipe;
    protected long finishTick = -1;

    protected static final int MAX_INPUT_STACK = 64;
    protected static final int MAX_OUTPUT_STACK = 64;

    public ProcessorMachine(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata) {
        super(dbId, ownerId, typeId, pos, metadata);
        this.inputBuffer = new MachineInventory(2);
        this.outputBuffer = new MachineInventory(1);

        if (this.metadata.has("input")) this.inputBuffer.loadState(this.metadata.getAsJsonObject("input"));
        if (this.metadata.has("output")) this.outputBuffer.loadState(this.metadata.getAsJsonObject("output"));
    }

    @Override
    public void tick(long currentTick) {
        tryEjectItem(currentTick);
        processRecipe(currentTick);
    }

    public boolean insertItem(MatterPayload item) { return false; }
    public abstract boolean insertItem(MatterPayload item, GridPosition fromPos);

    // NUOVO METODO ASTRATTO: Ogni macchina deve dire esattamente DOVE sputa l'output
    protected abstract GridPosition getOutputPosition();

    protected boolean insertIntoBuffer(int slotIndex, MatterPayload item) {
        if (inputBuffer.getCountInSlot(slotIndex) >= MAX_INPUT_STACK) return false;
        if (inputBuffer.insertIntoSlot(slotIndex, item)) {
            saveState();
            return true;
        }
        return false;
    }

    protected void processRecipe(long currentTick) {
        if (outputBuffer.getCount() >= MAX_OUTPUT_STACK) return;
        if (currentRecipe != null) {
            if (currentTick >= finishTick) completeProcessing();
        }
    }

    protected void completeProcessing() {
        if (outputBuffer.insert(currentRecipe.output())) {
            System.out.println(typeId + " -> FINITO: Creato " + currentRecipe.output().color());
            this.currentRecipe = null;
            this.finishTick = -1;
            saveState();
        }
    }

    protected void tryEjectItem(long currentTick) {
        if (outputBuffer.isEmpty()) return;
        if (gridManager == null) return;

        // FIX: Usa la posizione specifica calcolata dalla sottoclasse
        GridPosition targetPos = getOutputPosition();
        if (targetPos == null) return;

        PlacedMachine neighbor = getNeighborAt(targetPos);

        if (neighbor instanceof ConveyorBelt belt) {
            MatterPayload item = outputBuffer.extractFirst();
            if (item != null) {
                // Passiamo 'this.pos' (o meglio, la posizione precisa dell'output sarebbe ideale, ma this.pos va bene per ora)
                if (belt.insertItem(item, currentTick)) saveState();
                else outputBuffer.insert(item);
            }
        } else if (neighbor instanceof NexusMachine nexus) {
            MatterPayload item = outputBuffer.extractFirst();
            if (item != null) {
                // Nexus requires strict fromPos checking now.
                // We pass the output position as the "source"
                GridPosition outputSource = getOutputPosition().add(orientation.opposite().toVector());
                if (nexus.insertItem(item, outputSource)) saveState();
                else outputBuffer.insert(item);
            }
        }
    }

    protected void saveState() {
        this.metadata.add("input", inputBuffer.serialize());
        this.metadata.add("output", outputBuffer.serialize());
        markDirty();
    }
}