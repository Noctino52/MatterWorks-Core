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
 * PERFORMANCE FIX:
 * - Avoid MachineInventory + JSON churn in tick().
 * - Avoid allocating GridPosition every tick.
 * - Serialize state only in serialize(), and only when dirty.
 */
public class DropperMachine extends PlacedMachine {

    private static final int TRANSPORT_TIME = 5;

    // Single-slot buffer
    private MatterPayload storedItem;
    private transient JsonObject storedItemJsonCache;
    private int cooldownTicks = 0;

    // Runtime dirty flag (avoid JSON churn in hot path)
    private boolean runtimeStateDirty = false;

    // Cached ports
    private transient GridPosition cachedInputPosAbove; // input comes from above
    private transient Vector3Int cachedDir;
    private transient GridPosition cachedOutPos;

    // Neighbor cache (optional, helps when blocked)
    private transient long neighborCacheValidUntilTick = Long.MIN_VALUE;
    private transient PlacedMachine cachedNeighbor = null;
    private static final long NEIGHBOR_CACHE_TTL_TICKS = 20L;

    public DropperMachine(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata) {
        super(dbId, ownerId, typeId, pos, metadata);
        this.dimensions = new Vector3Int(1, 2, 1);

        recomputePorts();
        loadStateFromMetadata();
    }

    @Override
    public void setOrientation(Direction orientation) {
        super.setOrientation(orientation);
        recomputePorts();
        // orientation is already persisted in base metadata; mark dirty only if needed
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
        cachedInputPosAbove = new GridPosition(pos.x(), pos.y() + 1, pos.z());

        cachedDir = orientationToVector();
        cachedOutPos = new GridPosition(
                pos.x() + cachedDir.x(),
                pos.y() + cachedDir.y(),
                pos.z() + cachedDir.z()
        );

        neighborCacheValidUntilTick = Long.MIN_VALUE;
        cachedNeighbor = null;
    }

    private void loadStateFromMetadata() {
        // Legacy format: metadata.items is an array, slot0 is payload json (+count)
        if (this.metadata == null) return;
        try {
            if (this.metadata.has("items") && this.metadata.get("items").isJsonArray()) {
                JsonArray items = this.metadata.getAsJsonArray("items");
                if (items.size() > 0 && items.get(0).isJsonObject()) {
                    JsonObject obj = items.get(0).getAsJsonObject();
                    storedItem = MatterPayload.fromJson(obj);
                    storedItemJsonCache = obj; // reuse
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
            moved = nexus.insertItem(storedItem, this.pos);
        } else if (neighbor instanceof ProcessorMachine proc) {
            moved = proc.insertItem(storedItem, this.pos);
        } else if (neighbor instanceof Splitter split) {
            moved = split.insertItem(storedItem, this.pos);
        } else if (neighbor instanceof Merger merger) {
            moved = merger.insertItem(storedItem, this.pos);
        } else if (neighbor instanceof LiftMachine lift) {
            moved = lift.insertItem(storedItem, this.pos);
        } else if (neighbor instanceof DropperMachine dropper) {
            moved = dropper.insertItem(storedItem, this.pos);
        }

        if (moved) {
            storedItem = null;
            storedItemJsonCache = null;

            // cooldown is a counter -> accelerate once
            cooldownTicks = (int) computeAcceleratedTicks(TRANSPORT_TIME);

            runtimeStateDirty = true;
            markDirty();
        }
    }

    public boolean insertItem(MatterPayload item, GridPosition fromPos) {
        if (item == null || fromPos == null) return false;
        if (storedItem != null) return false;

        // input comes only from above
        if (!fromPos.equals(cachedInputPosAbove)) return false;

        // Original rule: only accept if sender is a belt
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
            // Keep legacy format: items[0] = payload json (+count=1) or null
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
            // Ensure minimum required fields exist for older saves/UI
            if (!metadata.has("capacity")) metadata.addProperty("capacity", 1);
            if (!metadata.has("orientation")) metadata.addProperty("orientation", this.orientation.name());
            if (!metadata.has("items")) {
                // materialize if missing
                runtimeStateDirty = true;
                return serialize();
            }
        }

        return metadata;
    }
}
