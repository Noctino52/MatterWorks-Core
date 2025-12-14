package com.matterworks.core.domain.matter;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record MatterPayload(
        MatterShape shape,
        MatterColor color,
        List<MatterEffect> effects
) {

    public MatterPayload(MatterShape shape, MatterColor color) {
        this(shape, color, Collections.emptyList());
    }

    public JsonObject serialize() {
        JsonObject json = new JsonObject();
        json.addProperty("shape", shape.name());
        json.addProperty("color", color.name());

        if (!effects.isEmpty()) {
            JsonArray effectsJson = new JsonArray();
            effects.forEach(e -> effectsJson.add(e.name()));
            json.add("effects", effectsJson);
        }
        return json;
    }

    public boolean isComplex() { return !effects.isEmpty(); }

    // --- NUOVO METODO: Deserializzazione ---
    public static MatterPayload fromJson(JsonObject json) {
        try {
            MatterShape s = MatterShape.valueOf(json.get("shape").getAsString());
            MatterColor c = MatterColor.valueOf(json.get("color").getAsString());

            List<MatterEffect> effs = new ArrayList<>();
            if (json.has("effects")) {
                JsonArray arr = json.getAsJsonArray("effects");
                arr.forEach(el -> effs.add(MatterEffect.valueOf(el.getAsString())));
            }
            return new MatterPayload(s, c, effs);
        } catch (Exception e) {
            System.err.println("Errore parsing payload: " + e.getMessage());
            return new MatterPayload(MatterShape.CUBE, MatterColor.RAW); // Fallback
        }
    }
}