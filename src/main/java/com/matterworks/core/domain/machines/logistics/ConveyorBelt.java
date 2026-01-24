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

/**
 * PERFORMANCE FIX:
 * - No MatterPayload.serialize() in insertItem() (hot path).
 * - Metadata is updated only in serialize().
 * - Neighbor cache remains.
 */
public class ConveyorBelt extends PlacedMachine {

    private MatterPayload currentItem;
    private transient JsonObject currentItemJsonCache; // lazily built for UI/save
    private long arrivalTick = -1;

    private static final int TRANSPORT_TIME = 20;

    // Backoff when blocked
    private int blockedStreak = 0;
    private static final int MAX_BLOCKED_STREAK = 5; // 2^5 => 32 ticks

    /**
     * Spread pushes across the full transport window to reduce burst ticks.
     * Range: 0..TRANSPORT_TIME-1
     */
    private final int phaseOffsetTicks;

    // Cached output direction & output position
    private Vector3Int cachedDir;
    private GridPosition cachedOutPos;

    // Neighbor cache
    private transient long neighborCacheValidUntilTick = Long.MIN_VALUE;
    private transient PlacedMachine cachedNeighbor = null;
    private static final long NEIGHBOR_CACHE_TTL_TICKS = 20L;

    public ConveyorBelt(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata) {
        super(dbId, ownerId, typeId, pos, metadata);
        this.dimensions = Vector3Int.one();

        int mixed = mixPos(pos);
        int phase = mixed % TRANSPORT_TIME;
        if (phase < 0) phase += TRANSPORT_TIME;
        this.phaseOffsetTicks = phase;

        recomputeCachedOutput();

        // Backward compatibility: if old save contains currentItem, restore it (no dirty)
        if (this.metadata != null && this.metadata.has("currentItem") && this.metadata.get("currentItem").isJsonObject()) {
            try {
                JsonObject obj = this.metadata.getAsJsonObject("currentItem");
                this.currentItem = MatterPayload.fromJson(obj);
                this.currentItemJsonCache = obj; // reuse
                this.arrivalTick = phaseOffsetTicks;
            } catch (Throwable ignored) {
                this.currentItem = null;
                this.currentItemJsonCache = null;
                this.metadata.remove("currentItem");
            }
        }
    }

    private static int mixPos(GridPosition p) {
        int x = p.x() * 73856093;
        int y = p.y() * 19349663;
        int z = p.z() * 83492791;
        return x ^ y ^ z;
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

    @Override
    public void tick(long currentTick) {
        if (currentItem == null) return;

        if (arrivalTick == -1) {
            arrivalTick = scheduleAfter(currentTick, TRANSPORT_TIME, "BELT_MOVE") + phaseOffsetTicks;
        }

        if (currentTick >= arrivalTick) {
            pushToNeighbor(currentTick);
        }
    }

    public boolean insertItem(MatterPayload item, long currentTick) {
        if (item == null) return false;
        if (this.currentItem != null) return false;

        this.currentItem = item;
        this.currentItemJsonCache = null; // invalidate cache
        this.blockedStreak = 0;

        // phase spread
        this.arrivalTick = scheduleAfter(currentTick, TRANSPORT_TIME, "BELT_MOVE") + phaseOffsetTicks;

        // IMPORTANT: no json serialization here (hot path)
        return true;
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
            scheduleBlockedRetry(currentTick);
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
            this.arrivalTick = -1;
            this.blockedStreak = 0;
            // metadata updated lazily in serialize()
        } else {
            scheduleBlockedRetry(currentTick);
        }
    }

    private void scheduleBlockedRetry(long currentTick) {
        if (blockedStreak < MAX_BLOCKED_STREAK) blockedStreak++;

        long delay = 1L << blockedStreak; // 2,4,8,16,32
        if (delay < 2L) delay = 2L;
        if (delay > 32L) delay = 32L;

        this.arrivalTick = currentTick + delay + phaseOffsetTicks;
    }

    @Override
    public JsonObject serialize() {
        // UI/save: include currentItem only here (lazy)
        if (currentItem != null) {
            if (currentItemJsonCache == null) {
                try {
                    currentItemJsonCache = currentItem.serialize();
                } catch (Throwable ignored) {
                    currentItemJsonCache = null;
                }
            }
            if (currentItemJsonCache != null) metadata.add("currentItem", currentItemJsonCache);
            else metadata.remove("currentItem");
        } else {
            metadata.remove("currentItem");
        }

        return super.serialize();
    }
}
