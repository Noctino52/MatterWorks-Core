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
    private static final int SALE_INTERVAL = 10;

    public NexusMachine(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata) {
        super(dbId, ownerId, typeId, pos, metadata);
        this.dimensions = new Vector3Int(3, 3, 3);
        this.storage = new MachineInventory(100);

        if (this.metadata.has("items")) {
            this.storage.loadState(this.metadata);
        }
    }

    @Override
    public void tick(long currentTick) {
        if (storage.isEmpty()) return;
        if (nextSaleTick == -1) nextSaleTick = currentTick + SALE_INTERVAL;

        if (currentTick >= nextSaleTick) {
            sellNextItem();
            nextSaleTick = currentTick + SALE_INTERVAL;
        }
    }

    /**
     * Metodo legacy (non dovrebbe essere usato dai nastri, ma per sicurezza)
     */
    public boolean insertItem(MatterPayload item) {
        return false; // Il Nexus ora richiede una posizione di origine!
    }

    /**
     * NUOVO: Inserimento con validazione geometrica rigorosa.
     */
    public boolean insertItem(MatterPayload item, GridPosition fromPos) {
        if (item.shape() == null) return false; // Rifiuta liquidi/dye
        if (fromPos == null) return false;

        // Validazione Porta: Deve entrare da una delle 6 porte centrali
        if (!isValidInputPort(fromPos)) {
            return false;
        }

        boolean success = storage.insert(item);
        if (success) {
            this.metadata = storage.serialize();
            markDirty();
        }
        return success;
    }

    private boolean isValidInputPort(GridPosition from) {
        int x = pos.x();
        int y = pos.y();
        int z = pos.z();

        // Coordinate relative (delta)
        int dx = from.x() - x;
        int dy = from.y() - y;
        int dz = from.z() - z;

        // Il Nexus accetta input solo ai livelli Y=0 e Y=1 (relativi alla base)
        if (dy < 0 || dy > 1) return false;

        // Logica Porte Centrali (Il Nexus è 3x3, centro è offset 1)
        // Nord (z-1) -> Entra in (1, y, 0) -> Delta da origine: x=1, z=-1
        if (dx == 1 && dz == -1) return true;
        // Sud (z+3) -> Entra in (1, y, 2) -> Delta da origine: x=1, z=3
        if (dx == 1 && dz == 3) return true;
        // Ovest (x-1) -> Entra in (0, y, 1) -> Delta da origine: x=-1, z=1
        if (dx == -1 && dz == 1) return true;
        // Est (x+3) -> Entra in (2, y, 1) -> Delta da origine: x=3, z=1
        if (dx == 3 && dz == 1) return true;

        return false;
    }

    private void sellNextItem() {
        if (gridManager == null) return;
        MarketManager market = gridManager.getMarketManager();
        if (market == null) return;

        MatterPayload itemToSell = storage.extractFirst();
        if (itemToSell != null) {
            market.sellItem(itemToSell, this.getOwnerId());
            this.metadata = storage.serialize();
            markDirty();
        }
    }
}