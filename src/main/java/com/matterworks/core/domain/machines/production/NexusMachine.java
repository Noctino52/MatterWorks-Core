package com.matterworks.core.domain.machines.production;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.domain.machines.base.PlacedMachine;
import com.matterworks.core.domain.matter.MatterPayload;
import com.matterworks.core.domain.shop.MarketManager;
import com.matterworks.core.synchronization.SimTime;
import com.matterworks.core.common.Vector3Int;

import java.util.UUID;

/**
 * Nexus input ports MUST match UI:
 * - 4 centered ports on the sides of a 3x3 footprint
 * - only on Y layers: pos.y() and pos.y()+1 (same as drawNexusPortsGrid)
 *
 * Inventory is slot-based for performance.
 * Selling is DT-based (seconds), not tick-based.
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

    // Legacy constant: 5 ticks @ 20 TPS => 0.25s base interval
    private static final int SALE_INTERVAL_TICKS = 5;

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

        // Base: SALE_INTERVAL_TICKS * baseTickSeconds()
        double baseIntervalSeconds = SALE_INTERVAL_TICKS * SimTime.baseTickSeconds();
        double intervalSeconds = baseIntervalSeconds / mult;

        if (saleCooldownSeconds < 0.0) saleCooldownSeconds = intervalSeconds;

        saleCooldownSeconds -= dt;

        // catch-up (bounded by inventory)
        while (saleCooldownSeconds <= 0.0 && !isEmpty()) {
            sellNextItem();
            saleCooldownSeconds += intervalSeconds;
        }
    }

    public synchronized boolean insertItem(MatterPayload item, GridPosition fromPos) {
        if (item == null) return false;
        if (fromPos == null) return false;

        // Nexus accepts only "matter" (must have shape)
        if (item.shape() == null) return false;

        // Hard rule: ONLY the 4 blue input ports
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
        markDirty();
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
        if (slotItem[idx] == null || slotCount[idx] <= 0) {
            itemsJson.set(idx, JsonNull.INSTANCE);
        } else {
            JsonObject obj = slotItem[idx].serialize();
            obj.addProperty("count", Math.max(0, Math.min(slotCount[idx], MAX_STACK)));
            itemsJson.set(idx, obj);
        }

        // keep metadata always pointing to the same itemsJson instance
        metadata.addProperty("capacity", CAPACITY_SLOTS);
        metadata.add("items", itemsJson);
    }

    private void loadStateFromMetadata() {
        if (this.metadata == null) this.metadata = new JsonObject();

        if (!metadata.has("items") || !metadata.get("items").isJsonArray()) {
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
                        itemsJson.set(i, obj);
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

        for (int i = lim; i < CAPACITY_SLOTS; i++) {
            slotItem[i] = null;
            slotCount[i] = 0;
            itemsJson.set(i, JsonNull.INSTANCE);
        }

        metadata.addProperty("capacity", CAPACITY_SLOTS);
        metadata.add("items", itemsJson);
    }

    /**
     * EXACTLY the 4 UI blue ports for a 3x3 footprint:
     * - North centered: (x+1, y/y+1, z-1)
     * - South centered: (x+1, y/y+1, z+3)
     * - West centered : (x-1, y/y+1, z+1)
     * - East centered : (x+3, y/y+1, z+1)
     *
     * Accept only on relY 0..1 (same as UI).
     */
    private boolean isValidInputPort(GridPosition from) {
        int baseX = pos.x();
        int baseY = pos.y();
        int baseZ = pos.z();

        int relY = from.y() - baseY;
        if (relY < 0 || relY > 1) return false;

        // Footprint is 3x3, so width/height are 3 on X/Z.
        // The centered edge cells inside footprint are:
        // (baseX+1, baseZ+0) north edge
        // (baseX+1, baseZ+2) south edge
        // (baseX+0, baseZ+1) west edge
        // (baseX+2, baseZ+1) east edge
        // The belt/source must be OUTSIDE footprint, one step away.
        GridPosition north = new GridPosition(baseX + 1, from.y(), baseZ - 1);
        GridPosition south = new GridPosition(baseX + 1, from.y(), baseZ + 3);
        GridPosition west  = new GridPosition(baseX - 1, from.y(), baseZ + 1);
        GridPosition east  = new GridPosition(baseX + 3, from.y(), baseZ + 1);

        return from.equals(north) || from.equals(south) || from.equals(west) || from.equals(east);
    }

    @Override
    public JsonObject serialize() {
        metadata.addProperty("capacity", CAPACITY_SLOTS);
        metadata.add("items", itemsJson);
        return super.serialize();
    }
}
