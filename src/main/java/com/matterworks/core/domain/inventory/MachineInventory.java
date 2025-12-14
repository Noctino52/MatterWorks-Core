package com.matterworks.core.domain.inventory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.matterworks.core.domain.matter.MatterPayload;

import java.util.ArrayList;
import java.util.List;

public class MachineInventory {

    private final List<MatterPayload> slots;
    private final int maxCapacity;

    public MachineInventory(int maxCapacity) {
        this.maxCapacity = maxCapacity;
        this.slots = new ArrayList<>();
    }

    public boolean insert(MatterPayload m) {
        if (slots.size() >= maxCapacity) return false;
        slots.add(m);
        return true;
    }

    /**
     * Estrae il primo oggetto disponibile (FIFO).
     * Usato per l'espulsione automatica verso nastri/inventari.
     */
    public MatterPayload extractFirst() {
        if (slots.isEmpty()) return null;
        return slots.remove(0);
    }

    public boolean isEmpty() { return slots.isEmpty(); }
    public int getCount() { return slots.size(); }

    public JsonObject serialize() {
        JsonObject json = new JsonObject();
        json.addProperty("capacity", maxCapacity);
        json.addProperty("count", slots.size());

        JsonArray items = new JsonArray();
        for (MatterPayload p : slots) {
            items.add(p.serialize());
        }
        json.add("items", items);
        return json;
    }

    public void loadState(JsonObject json) {
        if (json == null || !json.has("items")) return;

        this.slots.clear();
        JsonArray items = json.getAsJsonArray("items");

        for (JsonElement el : items) {
            if (el.isJsonObject()) {
                MatterPayload mp = MatterPayload.fromJson(el.getAsJsonObject());
                this.slots.add(mp);
            }
        }
    }
}