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

        // --- FIX NPE: Controllo null su shape ---
        if (shape != null) {
            json.addProperty("shape", shape.name());
        } else {
            // Se shape è null (liquido), non scriviamo nulla o scriviamo null esplicito
            // Gson gestisce l'assenza come null al deserializing solitamente.
        }

        // Colore è sempre presente (anche se RAW)
        if (color != null) {
            json.addProperty("color", color.name());
        }

        if (!effects.isEmpty()) {
            JsonArray effectsJson = new JsonArray();
            effects.forEach(e -> effectsJson.add(e.name()));
            json.add("effects", effectsJson);
        }
        return json;
    }

    public boolean isComplex() { return !effects.isEmpty(); }

    // --- FIX DESERIALIZZAZIONE: Supporto shape null ---
    public static MatterPayload fromJson(JsonObject json) {
        try {
            // 1. Gestione Shape Opzionale
            MatterShape s = null;
            if (json.has("shape") && !json.get("shape").isJsonNull()) {
                s = MatterShape.valueOf(json.get("shape").getAsString());
            }

            // 2. Gestione Colore
            MatterColor c = MatterColor.RAW; // Default fallback
            if (json.has("color")) {
                c = MatterColor.valueOf(json.get("color").getAsString());
            }

            // 3. Gestione Effetti
            List<MatterEffect> effs = new ArrayList<>();
            if (json.has("effects")) {
                JsonArray arr = json.getAsJsonArray("effects");
                arr.forEach(el -> effs.add(MatterEffect.valueOf(el.getAsString())));
            }

            return new MatterPayload(s, c, effs);

        } catch (Exception e) {
            System.err.println("Errore parsing payload: " + e.getMessage());
            // In caso di errore grave, ritorniamo un cubo raw per non crashare il server
            return new MatterPayload(MatterShape.CUBE, MatterColor.RAW);
        }
    }
}