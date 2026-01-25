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
 * Splitter (2x1x1):
 * - Accepts items only from the back (must come from a ConveyorBelt)
 * - Holds at most 1 item
 * - After TRANSPORT_TIME ticks, tries to push to Output A / Output B alternately
 *
 * PERFORMANCE FIX:
 * - No MatterPayload.serialize() in insertItem() (hot path).
 * - Cached ports, per-output neighbor caches (TTL).
 * - Metadata updated only in serialize() when dirty.
 */
public class Splitter extends PlacedMachine {

    private static final int TRANSPORT_TIME = 10;

    private MatterPayload currentItem;
    private transient JsonObject currentItemJsonCache;

    private long availableToPushAtTick = -1;
    private long nextPushAttemptTick = -1;

    private int outputIndex = 0; // 0 -> A, 1 -> B

    // backoff when blocked
    private int blockedStreak = 0;
    private static final int MAX_BLOCKED_STREAK = 5;

    // Cached ports
    private transient boolean portsValid = false;
    private transient GridPosition cachedInputPos;
    private transient GridPosition cachedOutA;
    private transient GridPosition cachedOutB;
    private transient GridPosition cachedSourceA;
    private transient GridPosition cachedSourceB;
    private transient GridPosition cachedExtensionPos;

    // Neighbor cache per output
    private transient long outACacheValidUntilTick = Long.MIN_VALUE;
    private transient PlacedMachine cachedOutANeighbor = null;
    private transient long outBCacheValidUntilTick = Long.MIN_VALUE;
    private transient PlacedMachine cachedOutBNeighbor = null;
    private static final long NEIGHBOR_CACHE_TTL_TICKS = 20L;

    // runtime dirty flag for metadata
    private boolean runtimeStateDirty = false;

    public Splitter(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata) {
        super(dbId, ownerId, typeId, pos, metadata);
        this.dimensions = new Vector3Int(2, 1, 1);

        // Load legacy "items" format (capacity=1)
        if (this.metadata != null && this.metadata.has("items") && this.metadata.get("items").isJsonArray()) {
            try {
                JsonArray arr = this.metadata.getAsJsonArray("items");
                if (arr.size() > 0 && arr.get(0).isJsonObject()) {
                    JsonObject obj = arr.get(0).getAsJsonObject();
                    this.currentItemJsonCache = obj;
                    this.currentItem = MatterPayload.fromJson(obj);

                    // travel will be initialized on tick
                    this.availableToPushAtTick = 0;
                    this.nextPushAttemptTick = 0;
                }
            } catch (Throwable ignored) {
                this.currentItem = null;
                this.currentItemJsonCache = null;
            }
        }

        if (this.metadata != null && this.metadata.has("outputIndex")) {
            try {
                this.outputIndex = this.metadata.get("outputIndex").getAsInt();
            } catch (Throwable ignored) {
                this.outputIndex = 0;
            }
        }
    }

    @Override
    public void setOrientation(Direction orientation) {
        super.setOrientation(orientation);
        portsValid = false;
        invalidateNeighborCaches();
        saveStateLazy();
    }

    @Override
    public void setOrientation(String orientation) {
        super.setOrientation(orientation);
        portsValid = false;
        invalidateNeighborCaches();
        saveStateLazy();
    }

    private void invalidateNeighborCaches() {
        outACacheValidUntilTick = Long.MIN_VALUE;
        cachedOutANeighbor = null;
        outBCacheValidUntilTick = Long.MIN_VALUE;
        cachedOutBNeighbor = null;
    }

    private void ensurePorts() {
        if (!portsValid) recomputePorts();
    }

    private void recomputePorts() {
        Vector3Int fwd = orientationToVector();
        Vector3Int back = new Vector3Int(-fwd.x(), -fwd.y(), -fwd.z());

        cachedExtensionPos = getExtensionPosition();

        // input is behind the main block
        cachedInputPos = pos.add(back.x(), back.y(), back.z());

        // outputs are in front of each block
        cachedOutA = pos.add(fwd.x(), fwd.y(), fwd.z());
        cachedOutB = cachedExtensionPos.add(fwd.x(), fwd.y(), fwd.z());

        // source positions used when inserting into processors/nexus
        cachedSourceA = pos;
        cachedSourceB = cachedExtensionPos;

        portsValid = true;
    }

    @Override
    public void tick(long currentTick) {
        if (currentItem == null) return;

        // Initialize travel if needed (because insertItem does not know currentTick)
        if (availableToPushAtTick == 0) {
            availableToPushAtTick = scheduleAfter(currentTick, TRANSPORT_TIME, "SPLITTER_MOVE");
            nextPushAttemptTick = availableToPushAtTick;
            return;
        }

        if (currentTick < availableToPushAtTick) return;
        if (currentTick < nextPushAttemptTick) return;

        attemptSmartPush(currentTick);
    }

    public boolean insertItem(MatterPayload item, GridPosition fromPos) {
        if (item == null || fromPos == null) return false;
        if (currentItem != null) return false;

        ensurePorts();

        if (!fromPos.equals(cachedInputPos)) return false;

        // Only accept if sender is a belt (keeps original behavior)
        PlacedMachine sender = getNeighborAt(fromPos);
        if (!(sender instanceof ConveyorBelt)) return false;

        currentItem = item;
        currentItemJsonCache = null; // invalidate cache
        blockedStreak = 0;

        // travel init on tick
        availableToPushAtTick = 0;
        nextPushAttemptTick = 0;

        saveStateLazy();
        return true;
    }

    private void attemptSmartPush(long currentTick) {
        ensurePorts();

        int primary = outputIndex & 1; // 0/1
        int secondary = primary ^ 1;

        boolean moved;

        if (primary == 0) {
            moved = pushToA(currentTick);
            if (moved) outputIndex = 1;
            else moved = pushToB(currentTick);
        } else {
            moved = pushToB(currentTick);
            if (moved) outputIndex = 0;
            else moved = pushToA(currentTick);
        }

        if (moved) {
            currentItem = null;
            currentItemJsonCache = null;

            availableToPushAtTick = -1;
            nextPushAttemptTick = -1;
            blockedStreak = 0;

            saveStateLazy();
        } else {
            scheduleBlockedRetry(currentTick);
        }
    }

    private boolean pushToA(long currentTick) {
        PlacedMachine n = getOutANeighborCached(currentTick);
        if (n == null || currentItem == null) return false;
        return pushInto(n, cachedSourceA, currentTick);
    }

    private boolean pushToB(long currentTick) {
        PlacedMachine n = getOutBNeighborCached(currentTick);
        if (n == null || currentItem == null) return false;
        return pushInto(n, cachedSourceB, currentTick);
    }

    private PlacedMachine getOutANeighborCached(long currentTick) {
        if (currentTick < outACacheValidUntilTick) return cachedOutANeighbor;
        PlacedMachine n = getNeighborAt(cachedOutA);
        cachedOutANeighbor = n;
        outACacheValidUntilTick = currentTick + NEIGHBOR_CACHE_TTL_TICKS;
        return n;
    }

    private PlacedMachine getOutBNeighborCached(long currentTick) {
        if (currentTick < outBCacheValidUntilTick) return cachedOutBNeighbor;
        PlacedMachine n = getNeighborAt(cachedOutB);
        cachedOutBNeighbor = n;
        outBCacheValidUntilTick = currentTick + NEIGHBOR_CACHE_TTL_TICKS;
        return n;
    }

    private boolean pushInto(PlacedMachine neighbor, GridPosition sourcePos, long currentTick) {
        if (neighbor instanceof ConveyorBelt belt) {
            return belt.insertItem(currentItem, currentTick);
        } else if (neighbor instanceof NexusMachine nexus) {
            return nexus.insertItem(currentItem, sourcePos);
        } else if (neighbor instanceof ProcessorMachine proc) {
            return proc.insertItem(currentItem, sourcePos);
        } else if (neighbor instanceof Splitter split) {
            return split.insertItem(currentItem, sourcePos);
        } else if (neighbor instanceof Merger merger) {
            return merger.insertItem(currentItem, sourcePos);
        } else if (neighbor instanceof LiftMachine lift) {
            return lift.insertItem(currentItem, sourcePos);
        } else if (neighbor instanceof DropperMachine dropper) {
            return dropper.insertItem(currentItem, sourcePos);
        }
        return false;
    }

    private void scheduleBlockedRetry(long currentTick) {
        blockedStreak = Math.min(MAX_BLOCKED_STREAK, blockedStreak + 1);
        long backoff = 1L << blockedStreak; // 2,4,8,16,32...

        long minTick = Math.max(currentTick + backoff, availableToPushAtTick);
        nextPushAttemptTick = minTick;
    }

    private GridPosition getExtensionPosition() {
        return switch (orientation) {
            case NORTH -> pos.add(1, 0, 0);
            case SOUTH -> pos.add(-1, 0, 0);
            case EAST -> pos.add(0, 0, 1);
            case WEST -> pos.add(0, 0, -1);
            default -> pos;
        };
    }

    private void saveStateLazy() {
        runtimeStateDirty = true;
        markDirty();
    }

    @Override
    public JsonObject serialize() {
        if (this.metadata == null) this.metadata = new JsonObject();

        if (runtimeStateDirty) {
            JsonArray items = new JsonArray();

            if (currentItem != null) {
                if (currentItemJsonCache == null) {
                    try {
                        currentItemJsonCache = currentItem.serialize();
                    } catch (Throwable ignored) {
                        currentItemJsonCache = null;
                    }
                }

                if (currentItemJsonCache != null) {
                    JsonObject obj = currentItemJsonCache.deepCopy();
                    obj.addProperty("count", 1);
                    items.add(obj);
                } else {
                    items.add(JsonNull.INSTANCE);
                }
            } else {
                items.add(JsonNull.INSTANCE);
            }

            this.metadata.add("items", items);
            this.metadata.addProperty("outputIndex", outputIndex);
            this.metadata.addProperty("orientation", this.orientation.name());

            runtimeStateDirty = false;
        } else {
            if (!this.metadata.has("orientation")) this.metadata.addProperty("orientation", this.orientation.name());
            if (!this.metadata.has("outputIndex")) this.metadata.addProperty("outputIndex", outputIndex);
            if (!this.metadata.has("items")) {
                JsonArray items = new JsonArray();
                items.add(JsonNull.INSTANCE);
                this.metadata.add("items", items);
            }
        }

        return super.serialize();
    }
}
