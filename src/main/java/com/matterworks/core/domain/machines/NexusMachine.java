package com.matterworks.core.domain.machines;

import com.google.gson.JsonObject;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.domain.inventory.MachineInventory;
import com.matterworks.core.domain.matter.MatterPayload;
import com.matterworks.core.domain.shop.MarketManager;

import java.util.UUID;

public class NexusMachine extends PlacedMachine {

    private final MachineInventory storage;
    private long nextSaleTick = -1;
    private static final int SALE_INTERVAL = 10; // Vende ogni 0.5s (veloce)

    public NexusMachine(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata) {
        super(dbId, ownerId, typeId, pos, metadata);
        // Dimensioni interne di sicurezza (vengono sovrascritte dal Registry comunque)
        this.dimensions = new Vector3Int(3, 3, 3);

        // Inventario capiente (100 slot)
        this.storage = new MachineInventory(100);

        if (this.metadata.has("items")) {
            this.storage.loadState(this.metadata);
        }
    }


    @Override
    public void tick(long currentTick) {
        if (storage.isEmpty()) return;

        if (nextSaleTick == -1) {
            nextSaleTick = currentTick + SALE_INTERVAL;
        }

        if (currentTick >= nextSaleTick) {
            sellNextItem();
            nextSaleTick = currentTick + SALE_INTERVAL;
        }
    }

    /**
     * Metodo chiamato dai Nastri per inserire roba qui.
     */
    public boolean insertItem(MatterPayload item) {
        System.out.println("Nexus: Ricevuto item " + item.shape()); // <--- DEBUG
        boolean success = storage.insert(item);
        if (success) {
            this.metadata = storage.serialize();
            markDirty();
        }
        return success;
    }

    private void sellNextItem() {
        if (gridManager == null) return;

        // Recupera il MarketManager dal contesto
        MarketManager market = gridManager.getMarketManager();
        if (market == null) return;

        MatterPayload itemToSell = storage.extractFirst();

        if (itemToSell != null) {
            market.sellItem(itemToSell, this.ownerId);

            this.metadata = storage.serialize();
            markDirty();
        }
    }

}