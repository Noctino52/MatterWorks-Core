package com.matterworks.core.domain.machines.logistics;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
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
 * PERFORMANCE:
 * - No MachineInventory (no JSON serialization per move)
 * - Cached ports (no per-tick GridPosition allocations)
 * - saveState() is "lazy": only marks dirty. Serialization happens in serialize().
 */
public class Splitter extends PlacedMachine {

    private static final int TRANSPORT_TIME = 10;

    private MatterPayload currentItem;
    private JsonObject currentItemJson;

    private long availableToPushAtTick = -1;
    private long nextPushAttemptTick = -1;

    private int outputIndex = 0; // 0 -> A, 1 -> B

    // backoff when blocked
    private int blockedStreak = 0;
    private static final int MAX_BLOCKED_STREAK = 5;

    // Cached ports
    private transient GridPosition cachedInputPos;
    private transient GridPosition cachedOutA;
    private transient GridPosition cachedOutB;
    private transient GridPosition cachedSourceA;
    private transient GridPosition cachedSourceB;
    private transient Vector3Int cachedFwd;
    private transient boolean portsValid = false;

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
                    this.currentItemJson = obj;
                    this.currentItem = MatterPayload.fromJson(obj);
                    // allow moving soon (we don't know original tick)
                    this.availableToPushAtTick = 0;
                    this.nextPushAttemptTick = 0;
                }
            } catch (Throwable ignored) {
                this.currentItem = null;
                this.currentItemJson = null;
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

    private void ensurePorts() {
        if (!portsValid) recomputePorts();
    }

    private void recomputePorts() {
        Vector3Int f = orientationToVector();
        Vector3Int back = new Vector3Int(-f.x(), -f.y(), -f.z());

        // input is behind the main block
        cachedInputPos = pos.add(back.x(), back.y(), back.z());

        // extension block (2nd block)
        GridPosition extensionPos = getExtensionPosition();

        // outputs are in front of each block
        cachedOutA = pos.add(f.x(), f.y(), f.z());
        cachedOutB = extensionPos.add(f.x(), f.y(), f.z());

        // source positions used when inserting into processors/nexus
        cachedSourceA = pos;
        cachedSourceB = extensionPos;

        cachedFwd = f;
        portsValid = true;
    }

    @Override
    public void setOrientation(String orientation) {
        super.setOrientation(orientation);
        portsValid = false;
        saveState();
    }

    @Override
    public void tick(long currentTick) {
        if (currentItem == null) return;

        if (nextPushAttemptTick == -1) {
            nextPushAttemptTick = Math.max(currentTick, availableToPushAtTick);
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
        blockedStreak = 0;

        availableToPushAtTick = scheduleAfter(0, TRANSPORT_TIME, "SPLITTER_MOVE"); // placeholder if schedule uses current tick
        // Better: use currentTick if you pass it (but signature doesn't). We use a safe fallback:
        // We'll just allow push after TRANSPORT_TIME relative to "now" at first tick.
        // So set to 0 sentinel and compute in tick:
        availableToPushAtTick = 0;
        nextPushAttemptTick = 0;

        // cache for UI
        try {
            currentItemJson = item.serialize();
        } catch (Throwable ignored) {
            currentItemJson = null;
        }

        saveState();
        return true;
    }

    private void attemptSmartPush(long currentTick) {
        ensurePorts();

        // If inserted and not yet initialized, initialize travel-time now
        if (availableToPushAtTick == 0) {
            availableToPushAtTick = scheduleAfter(currentTick, TRANSPORT_TIME, "SPLITTER_MOVE");
            nextPushAttemptTick = availableToPushAtTick;
            return;
        }

        int primary = outputIndex & 1; // 0/1
        int secondary = primary ^ 1;

        boolean moved = false;

        if (primary == 0) {
            moved = pushTo(cachedOutA, cachedSourceA, currentTick);
            if (moved) outputIndex = 1;
            else moved = pushTo(cachedOutB, cachedSourceB, currentTick);
        } else {
            moved = pushTo(cachedOutB, cachedSourceB, currentTick);
            if (moved) outputIndex = 0;
            else moved = pushTo(cachedOutA, cachedSourceA, currentTick);
        }

        if (moved) {
            currentItem = null;
            currentItemJson = null;

            availableToPushAtTick = -1;
            nextPushAttemptTick = -1;
            blockedStreak = 0;

            saveState();
        } else {
            scheduleBlockedRetry(currentTick);
        }
    }

    private boolean pushTo(GridPosition targetPos, GridPosition sourcePos, long currentTick) {
        if (currentItem == null) return false;

        PlacedMachine neighbor = getNeighborAt(targetPos);
        if (neighbor == null) return false;

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

    private void saveState() {
        runtimeStateDirty = true;
        markDirty();
    }

    @Override
    public JsonObject serialize() {
        if (this.metadata == null) this.metadata = new JsonObject();

        if (runtimeStateDirty) {
            // Keep legacy inventory format: items[0] = payload json (+count)
            JsonArray items = new JsonArray();
            if (currentItemJson != null) {
                JsonObject obj = currentItemJson.deepCopy();
                obj.addProperty("count", 1);
                items.add(obj);
            } else {
                items.add(JsonNull.INSTANCE);
            }
            this.metadata.add("items", items);

            this.metadata.addProperty("outputIndex", outputIndex);
            this.metadata.addProperty("orientation", this.orientation.name());

            runtimeStateDirty = false;
        } else {
            // Ensure presence for older UI expectations
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
