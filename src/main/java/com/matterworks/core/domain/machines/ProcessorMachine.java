package com.matterworks.core.domain.machines;

import com.google.gson.JsonObject;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.domain.inventory.MachineInventory;
import com.matterworks.core.domain.matter.MatterPayload;
import com.matterworks.core.domain.matter.Recipe;
import com.matterworks.core.domain.matter.RecipeRegistry;

import java.util.Collections;
import java.util.UUID;

public abstract class ProcessorMachine extends PlacedMachine {

    protected final MachineInventory inputBuffer;
    protected final MachineInventory outputBuffer;

    protected Recipe currentRecipe;
    protected long finishTick = -1;

    public ProcessorMachine(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata) {
        super(dbId, ownerId, typeId, pos, metadata);
        this.inputBuffer = new MachineInventory(2); // Default a 2 per sicurezza
        this.outputBuffer = new MachineInventory(1);

        if (this.metadata.has("input")) this.inputBuffer.loadState(this.metadata.getAsJsonObject("input"));
        if (this.metadata.has("output")) this.outputBuffer.loadState(this.metadata.getAsJsonObject("output"));
    }

    @Override
    public void tick(long currentTick) {
        // 1. Output Logistico
        tryEjectItem(currentTick);

        // 2. Logica di Processamento (Base)
        // Le sottoclassi possono fare Override se vogliono logiche custom (come il Chromator)
        processRecipe(currentTick);
    }

    public boolean insertItem(MatterPayload item) {
        if (inputBuffer.insert(item)) {
            saveState();
            return true;
        }
        return false;
    }

    protected void processRecipe(long currentTick) {
        if (currentRecipe != null) {
            if (currentTick >= finishTick) {
                completeProcessing();
            }
            return;
        }

        if (inputBuffer.isEmpty()) return;

        // Logica Standard (1 input -> 1 output)
        // Il Chromator farà Override di questo metodo per gestire 2 input.
    }

    protected void completeProcessing() {
        if (outputBuffer.insert(currentRecipe.output())) {
            System.out.println(typeId + " -> FINITO: Creato " + currentRecipe.output().color());
            this.currentRecipe = null;
            this.finishTick = -1;
            saveState();
        }
    }

    // --- FIX QUI: cambiato da private a protected ---
    protected void tryEjectItem(long currentTick) {
        if (outputBuffer.isEmpty()) return;
        if (gridManager == null) return;

        Vector3Int dir = orientation.toVector();
        GridPosition targetPos = new GridPosition(pos.x() + dir.x(), pos.y() + dir.y(), pos.z() + dir.z());

        PlacedMachine neighbor = gridManager.getMachineAt(targetPos);

        if (neighbor instanceof ConveyorBelt belt) {
            MatterPayload item = outputBuffer.extractFirst();
            if (item != null) {
                if (belt.insertItem(item, currentTick)) {
                    saveState();
                } else {
                    outputBuffer.insert(item); // Rollback
                }
            }
        } else if (neighbor instanceof NexusMachine nexus) {
            MatterPayload item = outputBuffer.extractFirst();
            if (item != null) {
                if (nexus.insertItem(item)) {
                    saveState();
                } else {
                    outputBuffer.insert(item);
                }
            }
        }
    }

    protected void saveState() {
        this.metadata.add("input", inputBuffer.serialize());
        this.metadata.add("output", outputBuffer.serialize());
        markDirty();
    }
}