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

        // Default: 1 slot input, 1 slot output (Le sottoclassi possono cambiarlo)
        this.inputBuffer = new MachineInventory(1);
        this.outputBuffer = new MachineInventory(1);

        // Caricamento Stato (se esistente)
        if (this.metadata.has("input")) this.inputBuffer.loadState(this.metadata.getAsJsonObject("input"));
        if (this.metadata.has("output")) this.outputBuffer.loadState(this.metadata.getAsJsonObject("output"));
    }

    @Override
    public void tick(long currentTick) {
        // 1. Logica di Output (Svuota il prodotto finito verso nastri/nexus)
        tryEjectItem(currentTick);

        // 2. Logica di Processamento
        processRecipe(currentTick);
    }

    /**
     * Chiamato dai Nastri (Conveyor) per inserire item qui.
     */
    public boolean insertItem(MatterPayload item) {
        // Accetta l'item solo se c'è spazio nell'input
        if (inputBuffer.insert(item)) {
            saveState();
            return true;
        }
        return false;
    }

    private void processRecipe(long currentTick) {
        // A. Se stiamo già lavorando
        if (currentRecipe != null) {
            if (currentTick >= finishTick) {
                completeProcessing();
            }
            return;
        }

        // B. Se siamo fermi, cerchiamo una nuova ricetta
        if (inputBuffer.isEmpty()) return;

        // Simula la lista degli input attuali (per ora prendiamo solo il primo per test)
        // In futuro: inputBuffer.getAllItems()
        var possibleRecipe = RecipeRegistry.findMatchingRecipe(Collections.singletonList(inputBuffer.extractFirst()));

        if (possibleRecipe.isPresent()) {
            // Trovata! Inizia il lavoro
            this.currentRecipe = possibleRecipe.get();
            // Calcola tick fine (20 tick = 1 secondo)
            this.finishTick = currentTick + (long)(currentRecipe.processTimeSeconds() * 20);

            // Nota: L'item è già stato estratto (consumato) sopra nel findMatchingRecipe simulato
            // In un sistema reale, lo estrarremmo qui.
            saveState();
        } else {
            // Nessuna ricetta valida per questo item?
            // Per ora lo rimettiamo dentro o lo lasciamo lì (qui l'abbiamo estratto per check, errore logico semplice)
            // Fix rapido per MVP: Se non trovi ricetta, rimettilo dentro o espellilo come scarto.
        }
    }

    private void completeProcessing() {
        // Mette il risultato nell'output
        if (outputBuffer.insert(currentRecipe.output())) {
            // Successo
            System.out.println(typeId + " -> FINITO: Creato " + currentRecipe.output().color());
            this.currentRecipe = null;
            this.finishTick = -1;
            saveState();
        } else {
            // Output pieno! La macchina si blocca (Stalled)
            // Non resettiamo la ricetta, riproveremo al prossimo tick
        }
    }

    private void tryEjectItem(long currentTick) {
        if (outputBuffer.isEmpty()) return;
        if (gridManager == null) return;

        Vector3Int dir = orientation.toVector();
        GridPosition targetPos = pos.add(dir);
        PlacedMachine neighbor = gridManager.getMachineAt(targetPos);

        // Logica di Push (simile al Drill)
        if (neighbor instanceof ConveyorBelt belt) {
            MatterPayload item = outputBuffer.extractFirst();
            if (item != null && belt.insertItem(item, currentTick)) {
                saveState();
            } else if (item != null) {
                outputBuffer.insert(item); // Rollback
            }
        } else if (neighbor instanceof NexusMachine nexus) {
            MatterPayload item = outputBuffer.extractFirst();
            if (item != null && nexus.insertItem(item)) {
                saveState();
            } else if (item != null) {
                outputBuffer.insert(item);
            }
        }
    }

    protected void saveState() {
        this.metadata.add("input", inputBuffer.serialize());
        this.metadata.add("output", outputBuffer.serialize());
        markDirty();
    }
}