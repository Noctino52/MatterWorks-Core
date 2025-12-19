package com.matterworks.core.domain.machines;

import com.google.gson.JsonObject;
import com.matterworks.core.common.Direction;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.domain.inventory.MachineInventory;
import com.matterworks.core.domain.matter.MatterPayload;

import java.util.UUID;

public class Splitter extends PlacedMachine {

    private final MachineInventory internalBuffer;
    private long arrivalTick = -1;
    private int outputIndex = 0; // 0 = Uscita A, 1 = Uscita B
    private static final int TRANSPORT_TIME = 10; // Più veloce di un nastro normale per fluidità

    public Splitter(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata) {
        super(dbId, ownerId, typeId, pos, metadata);
        // Dimensioni 2x1x1 come da DB
        this.dimensions = new Vector3Int(2, 1, 1);

        // Usiamo un inventario interno di 1 slot per consistenza con ProcessorMachine
        this.internalBuffer = new MachineInventory(1);

        // Caricamento Stato
        if (this.metadata.has("items")) {
            this.internalBuffer.loadState(this.metadata);
        }

        // Caricamento Memoria Round Robin
        if (this.metadata.has("outputIndex")) {
            this.outputIndex = this.metadata.get("outputIndex").getAsInt();
        }

        // Se c'è un item già caricato (da DB), resettiamo il timer per farlo ripartire
        if (!internalBuffer.isEmpty()) {
            this.arrivalTick = 0;
        }
    }

    /**
     * Inserimento Items.
     * Accetta input SOLO dal retro del blocco "Anchor" (Principale).
     */
    public boolean insertItem(MatterPayload item, GridPosition fromPos) {
        if (item == null) return false;

        // Se il buffer è pieno, rifiuta
        if (!internalBuffer.isEmpty()) return false;

        // Validazione Geometrica: Input valido solo dal retro dell'Anchor
        // Calcoliamo la posizione "dietro" rispetto all'orientamento
        GridPosition inputSpot = pos.add(orientation.opposite().toVector());

        if (!fromPos.equals(inputSpot)) {
            return false;
        }

        // Inserimento sicuro
        if (internalBuffer.insertIntoSlot(0, item)) {
            this.arrivalTick = 0; // Reset timer per nuovo item
            saveState();
            return true;
        }

        return false;
    }

    @Override
    public void tick(long currentTick) {
        // Se non ho nulla, non faccio nulla
        if (internalBuffer.isEmpty()) return;

        // Gestione tempo di transito
        if (arrivalTick == 0) {
            arrivalTick = currentTick + TRANSPORT_TIME;
        }

        if (currentTick >= arrivalTick) {
            attemptPushToNetwork(currentTick);
        }
    }

    private void attemptPushToNetwork(long currentTick) {
        // Calcolo posizioni di uscita
        // Uscita A: Davanti al blocco principale (Anchor)
        GridPosition outA = pos.add(orientation.toVector());

        // Uscita B: Davanti al blocco estensione (Il blocco accanto)
        GridPosition extensionPos = getExtensionPosition();
        GridPosition outB = extensionPos.add(orientation.toVector());

        GridPosition[] targets = { outA, outB };
        GridPosition[] sources = { pos, extensionPos }; // Passiamo la source corretta per Nexus/Processor

        // Tentativo Round Robin
        // Proviamo l'indice corrente. Se fallisce, NON cambiamo indice (blocchiamo il flusso come Factorio standard),
        // oppure potremmo provare l'altro (Smart Splitter).
        // Per ora implementiamo logica "Strict Round Robin" per determinismo.

        int currentTargetIdx = outputIndex % 2;
        GridPosition targetPos = targets[currentTargetIdx];
        GridPosition sourcePos = sources[currentTargetIdx];

        if (pushItem(targetPos, sourcePos, currentTick)) {
            // Successo: Rimuovi item, aggiorna indice, salva
            internalBuffer.decreaseSlot(0, 1);

            // Avanza Round Robin
            outputIndex = (outputIndex + 1) % 2;

            // Reset stato
            arrivalTick = -1;
            saveState();
        }
    }

    private boolean pushItem(GridPosition targetPos, GridPosition sourcePos, long currentTick) {
        PlacedMachine neighbor = getNeighborAt(targetPos);
        MatterPayload payload = internalBuffer.getItemInSlot(0);

        if (payload == null) return false;

        if (neighbor instanceof ConveyorBelt belt) {
            return belt.insertItem(payload, currentTick);
        }
        else if (neighbor instanceof NexusMachine nexus) {
            return nexus.insertItem(payload, sourcePos); // Il Nexus richiede Source valida
        }
        else if (neighbor instanceof ProcessorMachine proc) {
            return proc.insertItem(payload, sourcePos);
        }
        else if (neighbor instanceof Splitter split) {
            return split.insertItem(payload, sourcePos);
        }

        return false;
    }

    /**
     * Calcola la posizione del secondo blocco (2x1) basandosi sull'orientamento.
     * Assumiamo che la larghezza (2) si estenda verso "Destra" relativa.
     */
    private GridPosition getExtensionPosition() {
        int x = pos.x();
        int y = pos.y();
        int z = pos.z();

        return switch (orientation) {
            case NORTH -> new GridPosition(x + 1, y, z); // Estensione a Est
            case SOUTH -> new GridPosition(x - 1, y, z); // Estensione a Ovest
            case EAST  -> new GridPosition(x, y, z + 1); // Estensione a Sud
            case WEST  -> new GridPosition(x, y, z - 1); // Estensione a Nord
            default -> pos;
        };
    }

    private void saveState() {
        // Serializza inventario
        JsonObject invData = internalBuffer.serialize();
        this.metadata.add("items", invData.get("items"));
        // Serializza indice round robin
        this.metadata.addProperty("outputIndex", outputIndex);
        markDirty();
    }

    @Override
    public JsonObject serialize() {
        saveState(); // Assicura che i metadati siano aggiornati prima di salvare
        return super.serialize();
    }
}