package com.matterworks.core.domain.inventory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.matterworks.core.domain.matter.MatterPayload;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Inventory per macchine con logica di stacking.
 *
 * Compatibilità:
 * - Serializzazione: "capacity", "items", "count"
 * - Metodi read-only per inspection: snapshot(), getMatterCount(), getColorCount(), canInsert(), fromSerialized()
 *
 * Nuovo:
 * - maxStackSize configurabile per slot (default 64).
 */
public class MachineInventory {

    private static class InventorySlot {
        MatterPayload item;
        int count;

        InventorySlot(MatterPayload item, int count) {
            this.item = item;
            this.count = count;
        }
    }

    public record SnapshotEntry(int slotIndex, MatterPayload item, int count) {}

    private final List<InventorySlot> slots;
    private final int slotCount;
    private final int maxStackSize;

    public MachineInventory(int slotCount) {
        this(slotCount, 64);
    }

    public MachineInventory(int slotCount, int maxStackSize) {
        this.slotCount = Math.max(0, slotCount);
        this.maxStackSize = Math.max(1, maxStackSize);

        this.slots = new ArrayList<>(this.slotCount);
        for (int i = 0; i < this.slotCount; i++) {
            slots.add(null);
        }
    }

    // ==========================================================
    // READ-ONLY HELPERS (inspection)
    // ==========================================================

    public int getSlotCount() {
        return slotCount;
    }

    public int getMaxStackSize() {
        return maxStackSize;
    }

    public List<SnapshotEntry> snapshot() {
        if (slots.isEmpty()) return Collections.emptyList();
        List<SnapshotEntry> out = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            InventorySlot s = slots.get(i);
            if (s != null && s.item != null && s.count > 0) {
                out.add(new SnapshotEntry(i, s.item, s.count));
            }
        }
        return out;
    }

    /** Conta totale "Matter" (shape != null). */
    public int getMatterCount() {
        int total = 0;
        for (InventorySlot s : slots) {
            if (s == null || s.item == null) continue;
            if (s.item.shape() != null) total += Math.max(0, s.count);
        }
        return total;
    }

    /** Conta totale "Colori/Liquidi" (shape == null). */
    public int getColorCount() {
        int total = 0;
        for (InventorySlot s : slots) {
            if (s == null || s.item == null) continue;
            if (s.item.shape() == null) total += Math.max(0, s.count);
        }
        return total;
    }

    public boolean canInsert(MatterPayload newItem) {
        if (newItem == null) return false;

        for (int i = 0; i < slots.size(); i++) {
            InventorySlot currentSlot = slots.get(i);

            if (currentSlot == null) return true;

            if (currentSlot.item != null && isSameItem(currentSlot.item, newItem)) {
                if (currentSlot.count < maxStackSize) return true;
            }
        }
        return false;
    }

    public static MachineInventory fromSerialized(JsonObject json) {
        if (json == null || !json.has("items") || !json.get("items").isJsonArray()) {
            return new MachineInventory(0);
        }

        int cap;
        if (json.has("capacity")) {
            try { cap = json.get("capacity").getAsInt(); }
            catch (Exception ignored) { cap = json.getAsJsonArray("items").size(); }
        } else {
            cap = json.getAsJsonArray("items").size();
        }

        if (cap < 0) cap = 0;
        MachineInventory inv = new MachineInventory(cap);
        inv.loadState(json);
        return inv;
    }

    // ==========================================================
    // SLOT API (legacy + current behavior)
    // ==========================================================

    public MatterPayload getItemInSlot(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= slots.size()) return null;
        InventorySlot slot = slots.get(slotIndex);
        return (slot != null) ? slot.item : null;
    }

    public int getCountInSlot(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= slots.size()) return 0;
        InventorySlot slot = slots.get(slotIndex);
        return (slot != null) ? slot.count : 0;
    }

    public boolean insertIntoSlot(int slotIndex, MatterPayload newItem) {
        if (newItem == null) return false;

        while (slots.size() <= slotIndex) slots.add(null);

        InventorySlot currentSlot = slots.get(slotIndex);

        if (currentSlot == null) {
            slots.set(slotIndex, new InventorySlot(newItem, 1));
            return true;
        }

        if (currentSlot.item != null && isSameItem(currentSlot.item, newItem)) {
            if (currentSlot.count < maxStackSize) {
                currentSlot.count++;
                return true;
            }
        }

        return false;
    }

    public void decreaseSlot(int slotIndex, int amount) {
        if (slotIndex < 0 || slotIndex >= slots.size()) return;
        if (amount <= 0) return;

        InventorySlot slot = slots.get(slotIndex);
        if (slot != null) {
            slot.count -= amount;
            if (slot.count <= 0) slots.set(slotIndex, null);
        }
    }

    public boolean insert(MatterPayload m) {
        if (m == null) return false;
        for (int i = 0; i < slots.size(); i++) {
            if (insertIntoSlot(i, m)) return true;
        }
        return false;
    }

    public MatterPayload extractFirst() {
        for (int i = 0; i < slots.size(); i++) {
            InventorySlot slot = slots.get(i);
            if (slot != null && slot.count > 0) {
                MatterPayload item = slot.item;
                decreaseSlot(i, 1);
                return item;
            }
        }
        return null;
    }

    public boolean isEmpty() {
        for (InventorySlot s : slots) if (s != null) return false;
        return true;
    }

    public int getCount() {
        int total = 0;
        for (InventorySlot s : slots) if (s != null) total += s.count;
        return total;
    }

    private boolean isSameItem(MatterPayload a, MatterPayload b) {
        return a.color() == b.color() && a.shape() == b.shape();
        // effetti ignorati come da comportamento attuale
    }

    // ==========================================================
    // SERIALIZZAZIONE (compat + clamp)
    // ==========================================================

    public JsonObject serialize() {
        JsonObject json = new JsonObject();
        json.addProperty("capacity", slotCount);

        JsonArray itemsArr = new JsonArray();
        for (InventorySlot slot : slots) {
            if (slot != null && slot.item != null) {
                JsonObject slotJson = slot.item.serialize();
                int c = Math.max(0, Math.min(slot.count, maxStackSize));
                slotJson.addProperty("count", c);
                itemsArr.add(slotJson);
            } else {
                itemsArr.add(JsonNull.INSTANCE);
            }
        }
        json.add("items", itemsArr);
        return json;
    }

    public void loadState(JsonObject json) {
        if (json == null || !json.has("items")) return;

        slots.clear();
        for (int i = 0; i < slotCount; i++) slots.add(null);

        JsonArray itemsArr = json.getAsJsonArray("items");
        for (int i = 0; i < itemsArr.size() && i < slotCount; i++) {
            JsonElement el = itemsArr.get(i);
            if (el != null && el.isJsonObject()) {
                JsonObject obj = el.getAsJsonObject();
                MatterPayload mp = MatterPayload.fromJson(obj);
                int count = obj.has("count") ? obj.get("count").getAsInt() : 1;
                count = Math.max(0, Math.min(count, maxStackSize));
                slots.set(i, (count > 0) ? new InventorySlot(mp, count) : null);
            }
        }
    }
}
