package com.matterworks.core.domain.machines;

import com.google.gson.JsonObject;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.domain.matter.MatterPayload;

import java.lang.reflect.Method;
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
        if (neighbor == null) return;

        boolean moved = false;

        if (neighbor instanceof ConveyorBelt nextBelt) moved = nextBelt.insertItem(currentItem, currentTick);
        else if (neighbor instanceof NexusMachine nexus) moved = nexus.insertItem(currentItem, this.pos);
        else if (neighbor instanceof ProcessorMachine processor) moved = processor.insertItem(currentItem, this.pos);
        else if (neighbor instanceof Splitter splitter) moved = splitter.insertItem(currentItem, this.pos);
        else if (neighbor instanceof Merger merger) moved = merger.insertItem(currentItem, this.pos);
        else if (neighbor instanceof LiftMachine lift) moved = lift.insertItem(currentItem, this.pos);
        else if (neighbor instanceof DropperMachine dropper) moved = dropper.insertItem(currentItem, this.pos);
        else moved = tryGenericInsert(neighbor, currentItem, this.pos); // ✅ fallback

        if (moved) {
            this.currentItem = null;
            this.arrivalTick = -1;
            this.metadata.remove("currentItem");
            markDirty();
        }
    }

    private boolean tryGenericInsert(Object target, MatterPayload item, GridPosition fromPos) {
        try {
            Method m = target.getClass().getMethod("insertItem", MatterPayload.class, GridPosition.class);
            Object r = m.invoke(target, item, fromPos);
            return (r instanceof Boolean b) && b;
        } catch (NoSuchMethodException ignored) {
            return false;
        } catch (Throwable t) {
            t.printStackTrace();
            return false;
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
