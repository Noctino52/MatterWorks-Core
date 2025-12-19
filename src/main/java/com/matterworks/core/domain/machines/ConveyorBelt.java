package com.matterworks.core.domain.machines;

import com.google.gson.JsonObject;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.domain.matter.MatterPayload;

import java.util.UUID;

public class ConveyorBelt extends PlacedMachine {

    private MatterPayload currentItem;
    private long arrivalTick = -1;
    private static final int TRANSPORT_TIME = 20;

    public ConveyorBelt(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata) {
        super(dbId, ownerId, typeId, pos, metadata);
        this.dimensions = Vector3Int.one();

        if (this.metadata.has("currentItem")) {
            this.currentItem = MatterPayload.fromJson(this.metadata.getAsJsonObject("currentItem"));
            this.arrivalTick = 0;
        }
    }

    @Override
    public void tick(long currentTick) {
        if (currentItem == null) return;
        if (arrivalTick == -1) arrivalTick = currentTick + TRANSPORT_TIME;

        if (currentTick >= arrivalTick) {
            pushToNeighbor(currentTick);
        }
    }

    public boolean insertItem(MatterPayload item, long currentTick) {
        if (this.currentItem != null) return false;
        this.currentItem = item;
        this.arrivalTick = currentTick + TRANSPORT_TIME;
        this.metadata.add("currentItem", item.serialize());
        markDirty();
        return true;
    }

    private void pushToNeighbor(long currentTick) {
        if (gridManager == null) return;
        Vector3Int dirVec = orientation.toVector();
        GridPosition targetPos = new GridPosition(
                pos.x() + dirVec.x(),
                pos.y() + dirVec.y(),
                pos.z() + dirVec.z()
        );
        PlacedMachine neighbor = getNeighborAt(targetPos);
        boolean moved = false;

        if (neighbor instanceof ConveyorBelt nextBelt) {
            moved = nextBelt.insertItem(currentItem, currentTick);
        }
        else if (neighbor instanceof NexusMachine nexus) {
            moved = nexus.insertItem(currentItem, this.pos);
        }
        else if (neighbor instanceof ProcessorMachine processor) {
            moved = processor.insertItem(currentItem, this.pos);
        }
        // --- FIX: Aggiunta supporto per lo Splitter ---
        else if (neighbor instanceof Splitter splitter) {
            // Lo Splitter richiede la posizione di origine (this.pos)
            // per verificare se stiamo entrando dal retro.
            moved = splitter.insertItem(currentItem, this.pos);
        }
        // ----------------------------------------------

        if (moved) {
            this.currentItem = null;
            this.arrivalTick = -1;
            this.metadata.remove("currentItem");
            markDirty();
        }
    }

    @Override
    public JsonObject serialize() {
        super.serialize();
        if (currentItem != null) this.metadata.add("currentItem", currentItem.serialize());
        else this.metadata.remove("currentItem");
        return this.metadata;
    }
}