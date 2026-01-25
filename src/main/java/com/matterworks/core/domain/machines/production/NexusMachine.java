package com.matterworks.core.domain.machines.production;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.domain.machines.base.PlacedMachine;
import com.matterworks.core.domain.matter.MatterPayload;
import com.matterworks.core.domain.shop.MarketManager;
import com.matterworks.core.synchronization.SimTime;

import java.util.UUID;

/**
 * PERFORMANCE FIX:
 * - Removes MachineInventory (which serialized ALL slots every insert/sale).
 * - Keeps a fixed-slot inventory with stacking and updates ONLY the changed slot in metadata.
 *
 * DT-BASED SELL:
 * - Selling is driven by real seconds, not by currentTick.
 * - Respects tech tree / overclock speed multiplier via getEffectiveSpeedMultiplier().
 */
public class NexusMachine extends PlacedMachine {

    private static final int CAPACITY_SLOTS = 100;
    private static final int MAX_STACK = 64;

    private final MatterPayload[] slotItem = new MatterPayload[CAPACITY_SLOTS];
    private final int[] slotCount = new int[CAPACITY_SLOTS];

    // Metadata cache for UI/save (items array of length CAPACITY_SLOTS)
    private final JsonArray itemsJson = new JsonArray();

    // DT-based cooldown (seconds until next sale)
    private transient double saleCooldownSeconds = -1.0;

    // Legacy constant: 10 ticks at 20 TPS => 0.5 seconds (2 sales/sec)
    private static final int SALE_INTERVAL_TICKS = 10;

    public NexusMachine(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata) {
        super(dbId, ownerId, typeId, pos, metadata);
        this.dimensions = new Vector3Int(3, 3, 3);

        // init itemsJson with nulls
        for (int i = 0; i < CAPACITY_SLOTS; i++) itemsJson.add(JsonNull.INSTANCE);

        // load from metadata if present
        loadStateFromMetadata();
    }

    @Override
    public void tick(long currentTick) {
        if (isEmpty()) return;

        double dt = SimTime.deltaSeconds();
        if (dt <= 0) dt = 0.05;

        double mult = getEffectiveSpeedMultiplier();
        if (mult <= 0) mult = 1.0;

        // Base: 10 ticks * 50ms => 0.5 seconds
        double baseIntervalSeconds = SALE_INTERVAL_TICKS * SimTime.baseTickSeconds();
        // Faster nexus => shorter interval
        double intervalSeconds = baseIntervalSeconds / mult;

        if (saleCooldownSeconds < 0.0) {
            saleCooldownSeconds = intervalSeconds;
        }

        saleCooldownSeconds -= dt;

        // catch-up (bounded by inventory)
        while (saleCooldownSeconds <= 0.0 && !isEmpty()) {
            sellNextItem();
            saleCooldownSeconds += intervalSeconds;
        }
    }

    public boolean insertItem(MatterPayload item) {
        return false;
    }

    public synchronized boolean insertItem(MatterPayload item, GridPosition fromPos) {
        if (item == null) return false;
        if (item.shape() == null) return false; // nexus accepts only "matter"
        if (fromPos == null) return false;

        if (!isValidInputPort(fromPos)) return false;

        int idx = findInsertSlot(item);
        if (idx < 0) return false;

        if (slotItem[idx] == null) {
            slotItem[idx] = item;
            slotCount[idx] = 1;
        } else {
            slotCount[idx] = Math.min(MAX_STACK, slotCount[idx] + 1);
        }

        updateSlotJson(idx);
        markDirty(); // persist inventory state async

        return true;
    }

    private int findInsertSlot(MatterPayload item) {
        // 1) stack into existing
        for (int i = 0; i < CAPACITY_SLOTS; i++) {
            if (slotItem[i] != null && isSameItem(slotItem[i], item) && slotCount[i] < MAX_STACK) {
                return i;
            }
        }
        // 2) first empty
        for (int i = 0; i < CAPACITY_SLOTS; i++) {
            if (slotItem[i] == null || slotCount[i] <= 0) {
                return i;
            }
        }
        return -1;
    }

    private boolean isSameItem(MatterPayload a, MatterPayload b) {
        // Keep legacy behavior: effects ignored, shape+color only
        return a.shape() == b.shape() && a.color() == b.color();
    }

    private boolean isEmpty() {
        for (int i = 0; i < CAPACITY_SLOTS; i++) {
            if (slotItem[i] != null && slotCount[i] > 0) return false;
        }
        return true;
    }

    private int findFirstNonEmptySlot() {
        for (int i = 0; i < CAPACITY_SLOTS; i++) {
            if (slotItem[i] != null && slotCount[i] > 0) return i;
        }
        return -1;
    }

    private MatterPayload extractOneFromSlot(int idx) {
        if (idx < 0 || idx >= CAPACITY_SLOTS) return null;
        if (slotItem[idx] == null || slotCount[idx] <= 0) return null;

        MatterPayload out = slotItem[idx];
        slotCount[idx]--;

        if (slotCount[idx] <= 0) {
            slotItem[idx] = null;
            slotCount[idx] = 0;
        }

        updateSlotJson(idx);
        markDirty();
        return out;
    }

    private synchronized void sellNextItem() {
        if (gridManager == null) return;
        MarketManager market = gridManager.getMarketManager();
        if (market == null) return;

        int idx = findFirstNonEmptySlot();
        if (idx < 0) return;

        MatterPayload itemToSell = extractOneFromSlot(idx);
        if (itemToSell != null) {
            market.sellItem(itemToSell, this.getOwnerId());
        }
    }

    private void updateSlotJson(int idx) {
        // mutate only the touched slot, not all slots
        if (slotItem[idx] == null || slotCount[idx] <= 0) {
            itemsJson.set(idx, JsonNull.INSTANCE);
            return;
        }

        JsonObject obj = slotItem[idx].serialize();
        obj.addProperty("count", Math.max(0, Math.min(slotCount[idx], MAX_STACK)));
        itemsJson.set(idx, obj);

        // keep metadata always pointing to the same itemsJson instance
        metadata.addProperty("capacity", CAPACITY_SLOTS);
        metadata.add("items", itemsJson);
    }

    private void loadStateFromMetadata() {
        if (this.metadata == null) this.metadata = new JsonObject();

        if (!metadata.has("items") || !metadata.get("items").isJsonArray()) {
            // ensure base structure
            metadata.addProperty("capacity", CAPACITY_SLOTS);
            metadata.add("items", itemsJson);
            return;
        }

        JsonArray arr = metadata.getAsJsonArray("items");
        int lim = Math.min(arr.size(), CAPACITY_SLOTS);

        for (int i = 0; i < lim; i++) {
            JsonElement el = arr.get(i);
            if (el != null && el.isJsonObject()) {
                JsonObject obj = el.getAsJsonObject();
                try {
                    MatterPayload mp = MatterPayload.fromJson(obj);
                    int c = obj.has("count") ? obj.get("count").getAsInt() : 1;
                    c = Math.max(0, Math.min(c, MAX_STACK));
                    if (mp != null && c > 0) {
                        slotItem[i] = mp;
                        slotCount[i] = c;
                        itemsJson.set(i, obj); // reuse existing json
                    } else {
                        slotItem[i] = null;
                        slotCount[i] = 0;
                        itemsJson.set(i, JsonNull.INSTANCE);
                    }
                } catch (Throwable ignored) {
                    slotItem[i] = null;
                    slotCount[i] = 0;
                    itemsJson.set(i, JsonNull.INSTANCE);
                }
            } else {
                slotItem[i] = null;
                slotCount[i] = 0;
                itemsJson.set(i, JsonNull.INSTANCE);
            }
        }

        // fill remaining
        for (int i = lim; i < CAPACITY_SLOTS; i++) {
            slotItem[i] = null;
            slotCount[i] = 0;
            itemsJson.set(i, JsonNull.INSTANCE);
        }

        metadata.addProperty("capacity", CAPACITY_SLOTS);
        metadata.add("items", itemsJson);
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

    @Override
    public JsonObject serialize() {
        // Ensure metadata structure is always present
        metadata.addProperty("capacity", CAPACITY_SLOTS);
        metadata.add("items", itemsJson);
        return super.serialize();
    }
}
