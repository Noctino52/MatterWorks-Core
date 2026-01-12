package com.matterworks.core.domain.machines.production;

import com.google.gson.JsonObject;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.domain.inventory.MachineInventory;
import com.matterworks.core.domain.machines.base.PlacedMachine;
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

        if (nextSaleTick == -1) {
            nextSaleTick = scheduleAfter(currentTick, SALE_INTERVAL, "NEXUS_SELL");
        }

        if (currentTick >= nextSaleTick) {
            sellNextItem();
            nextSaleTick = scheduleAfter(currentTick, SALE_INTERVAL, "NEXUS_SELL");
        }
    }

    public boolean insertItem(MatterPayload item) {
        return false;
    }

    public boolean insertItem(MatterPayload item, GridPosition fromPos) {
        if (item.shape() == null) return false;
        if (fromPos == null) return false;

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

        int dx = from.x() - x;
        int dy = from.y() - y;
        int dz = from.z() - z;

        if (dy < 0 || dy > 1) return false;

        if (dx == 1 && dz == -1) return true;
        if (dx == 1 && dz == 3) return true;
        if (dx == -1 && dz == 1) return true;
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
