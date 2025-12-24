package com.matterworks.core.domain.inventory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.matterworks.core.domain.matter.MatterPayload;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Inventory per macchine con logica di stacking.
 *
 * NOTE IMPORTANTI:
 * - Mantiene la compatibilità con la serializzazione esistente ("capacity", "items", "count").
 * - Non cambia il comportamento di insert/stacking già in uso.
 * - Aggiunge SOLO metodi read-only (snapshot, conteggi, canInsert, fromSerialized) necessari per inspection/infobox.
 */
public class MachineInventory {

    // Classe interna per rappresentare uno Slot con quantità
    private static class InventorySlot {
        MatterPayload item;
        int count;

        InventorySlot(MatterPayload item, int count) {
            this.item = item;
            this.count = count;
        }
    }

    /**
     * Snapshot entry read-only per inspection/UI.
     */
    public record SnapshotEntry(int slotIndex, MatterPayload item, int count) {}

    private final List<InventorySlot> slots;
    private final int slotCount;
    private final int MAX_STACK_SIZE = 64; // Limite stack per slot

    public MachineInventory(int slotCount) {
        this.slotCount = Math.max(0, slotCount);
        this.slots = new ArrayList<>(this.slotCount);
        // Inizializza slot vuoti
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
        return MAX_STACK_SIZE;
    }

    /**
     * Snapshot degli slot non vuoti con count.
     */
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

    /**
     * Conta totale "Matter" (shape != null).
     */
    public int getMatterCount() {
        int total = 0;
        for (InventorySlot s : slots) {
            if (s == null || s.item == null) continue;
            if (s.item.shape() != null) total += Math.max(0, s.count);
        }
        return total;
    }

    /**
     * Conta totale "Colori/Liquidi" (shape == null).
     */
    public int getColorCount() {
        int total = 0;
        for (InventorySlot s : slots) {
            if (s == null || s.item == null) continue;
            if (s.item.shape() == null) total += Math.max(0, s.count);
        }
        return total;
    }

    /**
     * Verifica se l'inventario ACCETTEREBBE l'item secondo la logica attuale,
     * ma senza modificare lo stato (read-only).
     */
    public boolean canInsert(MatterPayload newItem) {
        if (newItem == null) return false;

        // Cerca il primo slot valido (Vuoto o Stesso tipo con spazio)
        for (int i = 0; i < slots.size(); i++) {
            InventorySlot currentSlot = slots.get(i);

            // Slot vuoto -> ok
            if (currentSlot == null) return true;

            // Slot occupato -> stacking se compatibile e c'è spazio
            if (currentSlot.item != null && isSameItem(currentSlot.item, newItem)) {
                if (currentSlot.count < MAX_STACK_SIZE) return true;
            }
        }
        return false;
    }

    /**
     * Helper comodo: costruisce un MachineInventory da un json serializzato
     * (richiede "items"; se manca "capacity" usa la size dell'array items).
     */
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

    /**
     * Tenta di inserire un item in uno slot specifico gestendo lo stacking.
     */
    public boolean insertIntoSlot(int slotIndex, MatterPayload newItem) {
        if (newItem == null) return false;

        // Assicuriamoci che la lista sia dimensionata
        while (slots.size() <= slotIndex) slots.add(null);

        InventorySlot currentSlot = slots.get(slotIndex);

        // CASO 1: Slot vuoto -> Crea nuovo stack
        if (currentSlot == null) {
            slots.set(slotIndex, new InventorySlot(newItem, 1));
            return true;
        }

        // CASO 2: Slot occupato -> Controlla se è lo stesso item e se c'è spazio
        if (currentSlot.item != null && isSameItem(currentSlot.item, newItem)) {
            if (currentSlot.count < MAX_STACK_SIZE) {
                currentSlot.count++;
                return true;
            }
        }

        // CASO 3: Item diverso o Stack pieno -> Rifiuta
        return false;
    }

    /**
     * Decrementa la quantità in uno slot (Consumo ricetta).
     */
    public void decreaseSlot(int slotIndex, int amount) {
        if (slotIndex < 0 || slotIndex >= slots.size()) return;

        InventorySlot slot = slots.get(slotIndex);
        if (slot != null) {
            slot.count -= amount;
            if (slot.count <= 0) {
                slots.set(slotIndex, null); // Slot svuotato
            }
        }
    }

    // --- METODI LEGACY (Per Belt/Drill che non usano slot specifici) ---

    public boolean insert(MatterPayload m) {
        // Cerca il primo slot valido (Vuoto o Stesso tipo con spazio)
        for (int i = 0; i < slots.size(); i++) {
            if (insertIntoSlot(i, m)) return true;
        }
        return false;
    }

    public MatterPayload extractFirst() {
        // Cerca il primo slot non vuoto e decrementa
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
        // Ritorna la somma totale degli item
        int total = 0;
        for (InventorySlot s : slots) if (s != null) total += s.count;
        return total;
    }

    // --- HELPER ---

    private boolean isSameItem(MatterPayload a, MatterPayload b) {
        return a.color() == b.color() && a.shape() == b.shape();
        // Nota: Ignoriamo effetti per ora per semplicità di stacking (comportamento già esistente)
    }

    // --- SERIALIZZAZIONE (Aggiornata con COUNT) ---

    public JsonObject serialize() {
        JsonObject json = new JsonObject();
        json.addProperty("capacity", slotCount);

        JsonArray itemsArr = new JsonArray();
        for (InventorySlot slot : slots) {
            if (slot != null) {
                JsonObject slotJson = slot.item.serialize();
                slotJson.addProperty("count", slot.count); // Salviamo quanti ce ne sono
                itemsArr.add(slotJson);
            } else {
                itemsArr.add((JsonElement) null);
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
                slots.set(i, new InventorySlot(mp, count));
            }
        }
    }
}
