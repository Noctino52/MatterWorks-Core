package com.matterworks.core.domain.machines.logistics;

import com.google.gson.JsonObject;
import com.matterworks.core.common.Direction;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.domain.machines.base.PlacedMachine;
import com.matterworks.core.domain.machines.base.ProcessorMachine;
import com.matterworks.core.domain.machines.production.NexusMachine;
import com.matterworks.core.domain.matter.MatterPayload;

import java.util.UUID;

public class ConveyorBelt extends PlacedMachine {

    // Fallback if DB is missing: 20 ticks => baseline 60/min behavior (your classic MK1)
    private static final long BASE_TRANSPORT_TICKS_FALLBACK = 20L;

    private MatterPayload currentItem;
    private transient JsonObject currentItemJsonCache;
    private long arrivalTick = -1L;

    private MatterPayload queuedItem;
    private transient JsonObject queuedItemJsonCache;

    private Vector3Int cachedDir;
    private GridPosition cachedOutPos;

    private transient long neighborCacheValidUntilTick = Long.MIN_VALUE;
    private transient PlacedMachine cachedNeighbor = null;
    private static final long NEIGHBOR_CACHE_TTL_TICKS = 20L;

    public ConveyorBelt(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata) {
        super(dbId, ownerId, typeId, pos, metadata);
        this.dimensions = Vector3Int.one();

        recomputeCachedOutput();

        if (this.metadata != null) {
            if (this.metadata.has("currentItem") && this.metadata.get("currentItem").isJsonObject()) {
                try {
                    JsonObject obj = this.metadata.getAsJsonObject("currentItem");
                    this.currentItem = MatterPayload.fromJson(obj);
                    this.currentItemJsonCache = obj;
                } catch (Throwable ignored) {
                    this.currentItem = null;
                    this.currentItemJsonCache = null;
                    this.metadata.remove("currentItem");
                }
            }

            if (this.metadata.has("queuedItem") && this.metadata.get("queuedItem").isJsonObject()) {
                try {
                    JsonObject obj = this.metadata.getAsJsonObject("queuedItem");
                    this.queuedItem = MatterPayload.fromJson(obj);
                    this.queuedItemJsonCache = obj;
                } catch (Throwable ignored) {
                    this.queuedItem = null;
                    this.queuedItemJsonCache = null;
                    this.metadata.remove("queuedItem");
                }
            }
        }
    }

    @Override
    public void setOrientation(Direction orientation) {
        super.setOrientation(orientation);
        recomputeCachedOutput();
    }

    @Override
    public void setOrientation(String orientation) {
        super.setOrientation(orientation);
        recomputeCachedOutput();
    }

    private void recomputeCachedOutput() {
        this.cachedDir = orientationToVector();
        this.cachedOutPos = new GridPosition(
                pos.x() + cachedDir.x(),
                pos.y() + cachedDir.y(),
                pos.z() + cachedDir.z()
        );

        this.neighborCacheValidUntilTick = Long.MIN_VALUE;
        this.cachedNeighbor = null;
    }

    public synchronized boolean insertItem(MatterPayload item, long currentTick) {
        if (item == null) return false;

        if (this.currentItem == null) {
            this.currentItem = item;
            this.currentItemJsonCache = null;
            this.arrivalTick = scheduleAfter(currentTick, computeTransportBaseTicks(), "BELT_MOVE");
            return true;
        }

        if (this.queuedItem == null) {
            this.queuedItem = item;
            this.queuedItemJsonCache = null;
            return true;
        }

        return false;
    }

    @Override
    public void tick(long currentTick) {
        if (currentItem == null && queuedItem != null) {
            currentItem = queuedItem;
            currentItemJsonCache = null;

            queuedItem = null;
            queuedItemJsonCache = null;

            arrivalTick = scheduleAfter(currentTick, computeTransportBaseTicks(), "BELT_MOVE");
        }

        if (currentItem == null) return;

        if (arrivalTick == -1L) {
            arrivalTick = scheduleAfter(currentTick, computeTransportBaseTicks(), "BELT_MOVE");
        }

        if (currentTick >= arrivalTick) {
            pushToNeighbor(currentTick);
        }
    }

    private long computeTransportBaseTicks() {
        // Tier-driven base ticks from DB, fallback to 20
        long base = getTierDrivenBaseTicks(BASE_TRANSPORT_TICKS_FALLBACK);
        return Math.max(1L, base);
    }

    private PlacedMachine getNeighborCached(long currentTick) {
        if (currentTick < neighborCacheValidUntilTick) return cachedNeighbor;

        PlacedMachine n = getNeighborAt(cachedOutPos);
        cachedNeighbor = n;
        neighborCacheValidUntilTick = currentTick + NEIGHBOR_CACHE_TTL_TICKS;
        return n;
    }

    private void pushToNeighbor(long currentTick) {
        if (gridManager == null) return;

        PlacedMachine neighbor = getNeighborCached(currentTick);
        if (neighbor == null) {
            arrivalTick = currentTick + 1;
            return;
        }

        boolean moved;
        if (neighbor instanceof ConveyorBelt nextBelt) moved = nextBelt.insertItem(currentItem, currentTick);
        else if (neighbor instanceof NexusMachine nexus) moved = nexus.insertItem(currentItem, this.pos);
        else if (neighbor instanceof ProcessorMachine processor) moved = processor.insertItem(currentItem, this.pos);
        else if (neighbor instanceof Splitter splitter) moved = splitter.insertItem(currentItem, this.pos);
        else if (neighbor instanceof Merger merger) moved = merger.insertItem(currentItem, this.pos);
        else if (neighbor instanceof LiftMachine lift) moved = lift.insertItem(currentItem, this.pos);
        else if (neighbor instanceof DropperMachine dropper) moved = dropper.insertItem(currentItem, this.pos);
        else moved = false;

        if (moved) {
            this.currentItem = null;
            this.currentItemJsonCache = null;
            this.arrivalTick = -1L;

            if (this.queuedItem != null) {
                this.currentItem = this.queuedItem;
                this.currentItemJsonCache = null;

                this.queuedItem = null;
                this.queuedItemJsonCache = null;

                this.arrivalTick = scheduleAfter(currentTick, computeTransportBaseTicks(), "BELT_MOVE");
            }
        } else {
            arrivalTick = currentTick + 1;
        }
    }

    @Override
    public synchronized JsonObject serialize() {
        if (currentItem != null) {
            if (currentItemJsonCache == null) {
                try { currentItemJsonCache = currentItem.serialize(); } catch (Throwable ignored) { currentItemJsonCache = null; }
            }
            if (currentItemJsonCache != null) metadata.add("currentItem", currentItemJsonCache);
            else metadata.remove("currentItem");
        } else {
            metadata.remove("currentItem");
        }

        if (queuedItem != null) {
            if (queuedItemJsonCache == null) {
                try { queuedItemJsonCache = queuedItem.serialize(); } catch (Throwable ignored) { queuedItemJsonCache = null; }
            }
            if (queuedItemJsonCache != null) metadata.add("queuedItem", queuedItemJsonCache);
            else metadata.remove("queuedItem");
        } else {
            metadata.remove("queuedItem");
        }

        return super.serialize();
    }
}
