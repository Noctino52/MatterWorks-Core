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
import com.matterworks.core.ui.CoreConfig;

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

        // Caricamento Dati
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
        saveInternalState(); // Salva subito
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
        MatterShape shape = (this.resourceToMine == MatterColor.RAW) ? MatterShape.CUBE : null;
        MatterPayload newItem = new MatterPayload(shape, this.resourceToMine);

        if (outputBuffer.insert(newItem)) {
            saveInternalState(); // Usa il metodo sicuro
        }
    }

    private void tryEjectItem(long currentTick) {
        if (outputBuffer.isEmpty()) return;

        // FIX: Usa getNeighborAt per trovare il nastro del proprietario corretto
        // (Assumendo che tu abbia applicato la patch Multi-Tenant precedente)
        Vector3Int dir = orientation.toVector();
        GridPosition targetPos = new GridPosition(pos.x() + dir.x(), pos.y() + dir.y(), pos.z() + dir.z());

        // Se non hai ancora il metodo getNeighborAt nella classe padre, usa:
        // PlacedMachine neighbor = gridManager.getMachineAt(this.ownerId, targetPos);
        PlacedMachine neighbor = getNeighborAt(targetPos);

        if (neighbor instanceof ConveyorBelt belt) {
            MatterPayload item = outputBuffer.extractFirst();
            if (item != null) {
                if (belt.insertItem(item, currentTick)) {
                    saveInternalState(); // FIX: Usa il metodo sicuro invece di sovrascrivere
                } else {
                    outputBuffer.insert(item); // Rollback se il nastro è pieno
                }
            }
        }
    }

    /**
     * Metodo centrale per aggiornare i metadati senza perdere pezzi.
     * Unisce lo stato dell'inventario con la configurazione della risorsa.
     */
    private void saveInternalState() {
        // 1. Ottieni il JSON dell'inventario
        JsonObject invState = outputBuffer.serialize();

        // 2. Fai il merge dentro this.metadata invece di sovrascriverlo brutalmente
        this.metadata.add("items", invState.get("items"));
        this.metadata.add("capacity", invState.get("capacity"));

        // 3. Assicura che la risorsa sia sempre salvata
        this.metadata.addProperty("mining_resource", resourceToMine.name());

        // 4. Marca per il salvataggio DB
        markDirty();
    }
}