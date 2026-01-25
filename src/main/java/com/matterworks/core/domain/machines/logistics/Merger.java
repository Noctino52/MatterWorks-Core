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
 * Merger (2x1x1):
 * - Two inputs (behind each of the 2 blocks), one output (front of main block).
 * - Holds at most 1 item.
 * - Accepts only from the preferred input side (alternates).
 *
 * PERFORMANCE FIX:
 * - Remove MachineInventory (JSON churn).
 * - No MatterPayload.serialize() in hot path.
 * - Cached ports and neighbor cache for output.
 * - Metadata updated only in serialize() when dirty.
 */
public class Merger extends PlacedMachine {

    private static final int TRANSPORT_TIME = 10;

    // Buffer
    private MatterPayload storedItem;
    private transient JsonObject storedItemJsonCache;

    // Timing
    private long availableToPushAtTick = -1;
    private long nextPushAttemptTick = -1;

    // backoff when blocked
    private int blockedStreak = 0;
    private static final int MAX_BLOCKED_STREAK = 5;

    // Preference/starvation
    private int preferredInputIndex = 0;
    private int starvationTicks = 0;
    private static final int STARVATION_THRESHOLD = 5;

    // Cached ports
    private transient boolean portsValid = false;
    private transient GridPosition cachedInput0;
    private transient GridPosition cachedInput1;
    private transient GridPosition cachedOut;
    private transient GridPosition cachedExtPos;
    private transient Vector3Int cachedFwd;
    private transient Vector3Int cachedBack;

    // Output neighbor cache
    private transient long outNeighborCacheValidUntilTick = Long.MIN_VALUE;
    private transient PlacedMachine cachedOutNeighbor = null;
    private static final long NEIGHBOR_CACHE_TTL_TICKS = 20L;

    // Dirty flag
    private boolean runtimeStateDirty = false;

    public Merger(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata) {
        super(dbId, ownerId, typeId, pos, metadata);
        this.dimensions = new Vector3Int(2, 1, 1);

        // Load legacy "items" format (capacity=1)
        if (this.metadata != null && this.metadata.has("items") && this.metadata.get("items").isJsonArray()) {
            try {
                JsonArray arr = this.metadata.getAsJsonArray("items");
                if (arr.size() > 0 && arr.get(0).isJsonObject()) {
                    JsonObject obj = arr.get(0).getAsJsonObject();
                    this.storedItemJsonCache = obj;
                    this.storedItem = MatterPayload.fromJson(obj);
                    this.availableToPushAtTick = 0; // will be initialized on tick
                    this.nextPushAttemptTick = 0;
                }
            } catch (Throwable ignored) {
                this.storedItem = null;
                this.storedItemJsonCache = null;
            }
        }

        if (this.metadata != null && this.metadata.has("preferredInput")) {
            try {
                this.preferredInputIndex = this.metadata.get("preferredInput").getAsInt();
            } catch (Throwable ignored) {
                this.preferredInputIndex = 0;
            }
        }
    }

    @Override
    public void setOrientation(Direction orientation) {
        super.setOrientation(orientation);
        portsValid = false;
        invalidateNeighborCache();
        saveStateLazy();
    }

    @Override
    public void setOrientation(String orientation) {
        super.setOrientation(orientation);
        portsValid = false;
        invalidateNeighborCache();
        saveStateLazy();
    }

    private void invalidateNeighborCache() {
        outNeighborCacheValidUntilTick = Long.MIN_VALUE;
        cachedOutNeighbor = null;
    }

    private void ensurePorts() {
        if (!portsValid) recomputePorts();
    }

    private void recomputePorts() {
        cachedFwd = orientationToVector();
        cachedBack = new Vector3Int(-cachedFwd.x(), -cachedFwd.y(), -cachedFwd.z());

        cachedExtPos = getExtensionPosition();

        // Inputs are behind each block (main + extension)
        cachedInput0 = pos.add(cachedBack.x(), cachedBack.y(), cachedBack.z());
        cachedInput1 = cachedExtPos.add(cachedBack.x(), cachedBack.y(), cachedBack.z());

        // Output is in front of main block
        cachedOut = pos.add(cachedFwd.x(), cachedFwd.y(), cachedFwd.z());

        portsValid = true;
    }

    public boolean insertItem(MatterPayload item, GridPosition fromPos) {
        if (item == null || fromPos == null) return false;
        if (storedItem != null) return false;

        ensurePorts();

        // Only accept if sender is a belt (keeps original behavior)
        PlacedMachine sender = getNeighborAt(fromPos);
        if (!(sender instanceof ConveyorBelt)) return false;

        int inputIndex = getInputIndexFromPos(fromPos);
        if (inputIndex == -1) return false;

        // Keep original: accept only from preferred input
        if (inputIndex != preferredInputIndex) return false;

        storedItem = item;
        storedItemJsonCache = null;

        togglePreference();
        starvationTicks = 0;
        blockedStreak = 0;

        // Initialize travel on first tick (we don't have currentTick here)
        availableToPushAtTick = 0;
        nextPushAttemptTick = 0;

        saveStateLazy();
        return true;
    }

    @Override
    public void tick(long currentTick) {
        if (storedItem != null) {
            // Initialize travel if needed
            if (availableToPushAtTick == 0) {
                availableToPushAtTick = scheduleAfter(currentTick, TRANSPORT_TIME, "MERGER_MOVE");
                nextPushAttemptTick = availableToPushAtTick;
                return;
            }

            if (currentTick < availableToPushAtTick) return;
            if (currentTick < nextPushAttemptTick) return;

            attemptPushOutput(currentTick);
        } else {
            starvationTicks++;
            if (starvationTicks >= STARVATION_THRESHOLD) {
                togglePreference();
                starvationTicks = 0;
                saveStateLazy();
            }
        }
    }

    private void attemptPushOutput(long currentTick) {
        ensurePorts();

        PlacedMachine neighbor = getOutNeighborCached(currentTick);
        if (neighbor == null || storedItem == null) {
            scheduleBlockedRetry(currentTick);
            return;
        }

        boolean pushed;
        if (neighbor instanceof ConveyorBelt belt) pushed = belt.insertItem(storedItem, currentTick);
        else if (neighbor instanceof NexusMachine nexus) pushed = nexus.insertItem(storedItem, this.pos);
        else if (neighbor instanceof ProcessorMachine proc) pushed = proc.insertItem(storedItem, this.pos);
        else if (neighbor instanceof Splitter split) pushed = split.insertItem(storedItem, this.pos);
        else if (neighbor instanceof Merger merger) pushed = merger.insertItem(storedItem, this.pos);
        else if (neighbor instanceof LiftMachine lift) pushed = lift.insertItem(storedItem, this.pos);
        else if (neighbor instanceof DropperMachine dropper) pushed = dropper.insertItem(storedItem, this.pos);
        else pushed = false;

        if (pushed) {
            storedItem = null;
            storedItemJsonCache = null;

            availableToPushAtTick = -1;
            nextPushAttemptTick = -1;
            blockedStreak = 0;

            saveStateLazy();
        } else {
            scheduleBlockedRetry(currentTick);
        }
    }

    private PlacedMachine getOutNeighborCached(long currentTick) {
        if (currentTick < outNeighborCacheValidUntilTick) return cachedOutNeighbor;

        PlacedMachine n = getNeighborAt(cachedOut);
        cachedOutNeighbor = n;
        outNeighborCacheValidUntilTick = currentTick + NEIGHBOR_CACHE_TTL_TICKS;
        return n;
    }

    private void scheduleBlockedRetry(long currentTick) {
        blockedStreak = Math.min(MAX_BLOCKED_STREAK, blockedStreak + 1);
        long backoff = 1L << blockedStreak; // 2,4,8,16,32

        long minTick = Math.max(currentTick + backoff, availableToPushAtTick);
        nextPushAttemptTick = minTick;
    }

    private void togglePreference() {
        preferredInputIndex = (preferredInputIndex + 1) & 1;
    }

    private int getInputIndexFromPos(GridPosition checkPos) {
        if (checkPos.equals(cachedInput0)) return 0;
        if (checkPos.equals(cachedInput1)) return 1;
        return -1;
    }

    private GridPosition getExtensionPosition() {
        return switch (orientation) {
            case NORTH -> pos.add(1, 0, 0);
            case SOUTH -> pos.add(-1, 0, 0);
            case EAST  -> pos.add(0, 0, 1);
            case WEST  -> pos.add(0, 0, -1);
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
            // Legacy format: items[0] = payload json (+count)
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

            this.metadata.add("items", items);
            this.metadata.addProperty("preferredInput", preferredInputIndex);
            this.metadata.addProperty("orientation", this.orientation.name());

            runtimeStateDirty = false;
        } else {
            // Ensure fields exist for older UI expectations
            if (!this.metadata.has("orientation")) this.metadata.addProperty("orientation", this.orientation.name());
            if (!this.metadata.has("preferredInput")) this.metadata.addProperty("preferredInput", preferredInputIndex);
            if (!this.metadata.has("items")) {
                JsonArray items = new JsonArray();
                items.add(JsonNull.INSTANCE);
                this.metadata.add("items", items);
            }
        }

        return super.serialize();
    }
}
