package com.matterworks.core.domain.machines.logistics;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
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
 * Lift:
 * - Takes input from the same Y level as base.
 * - Outputs to (forward + up).
 * - Holds at most 1 item.
 *
 * BALANCE:
 * - Tier-driven base ticks from DB for transport.
 * - Overclock applied via computeAcceleratedTicks.
 */
public class LiftMachine extends PlacedMachine {

    private static final long TRANSPORT_TICKS_FALLBACK = 5L;

    private MatterPayload storedItem;
    private transient JsonObject storedItemJsonCache;
    private int cooldownTicks = 0;

    private boolean runtimeStateDirty = false;

    // Cached ports/positions
    private transient Vector3Int cachedDir;
    private transient GridPosition cachedOutPos;     // forward + up
    private transient GridPosition cachedSourceTop; // pos.y + 1

    // Neighbor cache
    private transient long neighborCacheValidUntilTick = Long.MIN_VALUE;
    private transient PlacedMachine cachedNeighbor = null;
    private static final long NEIGHBOR_CACHE_TTL_TICKS = 20L;

    public LiftMachine(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata) {
        super(dbId, ownerId, typeId, pos, metadata);
        this.dimensions = new Vector3Int(1, 2, 1);

        recomputePorts();
        loadStateFromMetadata();
    }

    @Override
    public void setOrientation(Direction orientation) {
        super.setOrientation(orientation);
        recomputePorts();
        runtimeStateDirty = true;
        markDirty();
    }

    @Override
    public void setOrientation(String orientation) {
        super.setOrientation(orientation);
        recomputePorts();
        runtimeStateDirty = true;
        markDirty();
    }

    private void recomputePorts() {
        cachedDir = orientationToVector();

        cachedOutPos = new GridPosition(
                pos.x() + cachedDir.x(),
                pos.y() + 1 + cachedDir.y(),
                pos.z() + cachedDir.z()
        );

        cachedSourceTop = new GridPosition(pos.x(), pos.y() + 1, pos.z());

        neighborCacheValidUntilTick = Long.MIN_VALUE;
        cachedNeighbor = null;
    }

    private void loadStateFromMetadata() {
        if (this.metadata == null) return;
        try {
            if (this.metadata.has("items") && this.metadata.get("items").isJsonArray()) {
                JsonArray items = this.metadata.getAsJsonArray("items");
                if (items.size() > 0 && items.get(0).isJsonObject()) {
                    JsonObject obj = items.get(0).getAsJsonObject();
                    storedItem = MatterPayload.fromJson(obj);
                    storedItemJsonCache = obj;
                }
            }
        } catch (Throwable ignored) {
            storedItem = null;
            storedItemJsonCache = null;
        }
    }

    @Override
    public void tick(long currentTick) {
        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }

        if (storedItem == null) return;

        PlacedMachine neighbor = getNeighborCached(currentTick);
        if (neighbor == null) return;

        boolean moved = false;

        if (neighbor instanceof ConveyorBelt belt) {
            moved = belt.insertItem(storedItem, currentTick);
        } else if (neighbor instanceof NexusMachine nexus) {
            moved = nexus.insertItem(storedItem, cachedSourceTop);
        } else if (neighbor instanceof ProcessorMachine proc) {
            moved = proc.insertItem(storedItem, cachedSourceTop);
        } else if (neighbor instanceof Splitter split) {
            moved = split.insertItem(storedItem, cachedSourceTop);
        } else if (neighbor instanceof Merger merger) {
            moved = merger.insertItem(storedItem, cachedSourceTop);
        } else if (neighbor instanceof LiftMachine lift) {
            moved = lift.insertItem(storedItem, cachedSourceTop);
        } else if (neighbor instanceof DropperMachine dropper) {
            moved = dropper.insertItem(storedItem, cachedSourceTop);
        }

        if (moved) {
            storedItem = null;
            storedItemJsonCache = null;

            long base = getTierDrivenBaseTicks(TRANSPORT_TICKS_FALLBACK);
            cooldownTicks = (int) computeAcceleratedTicks(base);

            runtimeStateDirty = true;
            markDirty();
        }
    }

    public boolean insertItem(MatterPayload item, GridPosition fromPos) {
        if (item == null || fromPos == null) return false;
        if (storedItem != null) return false;

        // Original rule: input at same Y level
        if (fromPos.y() != this.pos.y()) return false;

        // Only accept if sender is a belt
        PlacedMachine sender = getNeighborAt(fromPos);
        if (!(sender instanceof ConveyorBelt)) return false;

        storedItem = item;
        storedItemJsonCache = null;

        runtimeStateDirty = true;
        markDirty();
        return true;
    }

    private PlacedMachine getNeighborCached(long currentTick) {
        if (currentTick < neighborCacheValidUntilTick) return cachedNeighbor;

        PlacedMachine n = getNeighborAt(cachedOutPos);
        cachedNeighbor = n;
        neighborCacheValidUntilTick = currentTick + NEIGHBOR_CACHE_TTL_TICKS;
        return n;
    }

    @Override
    public JsonObject serialize() {
        if (metadata == null) metadata = new JsonObject();

        if (runtimeStateDirty) {
            JsonArray items = new JsonArray();

            if (storedItem != null) {
                if (storedItemJsonCache == null) {
                    try {
                        storedItemJsonCache = storedItem.serialize();
                    } catch (Throwable ignored) {
                        storedItemJsonCache = null;
                    }
                }
                if (storedItemJsonCache != null) {
                    JsonObject obj = storedItemJsonCache.deepCopy();
                    obj.addProperty("count", 1);
                    items.add(obj);
                } else {
                    items.add(JsonNull.INSTANCE);
                }
            } else {
                items.add(JsonNull.INSTANCE);
            }

            metadata.add("items", items);
            metadata.addProperty("capacity", 1);
            metadata.addProperty("orientation", this.orientation.name());

            runtimeStateDirty = false;
        } else {
            if (!metadata.has("capacity")) metadata.addProperty("capacity", 1);
            if (!metadata.has("orientation")) metadata.addProperty("orientation", this.orientation.name());
            if (!metadata.has("items")) {
                runtimeStateDirty = true;
                return serialize();
            }
        }

        return metadata;
    }
}
