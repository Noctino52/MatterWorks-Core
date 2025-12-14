package com.matterworks.core.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class PlotObject {

    private Long id;
    private Long plotId;
    private int x;
    private int y;
    private int z;
    private String typeId;
    private JsonObject metaData;

    // --- COSTRUTTORI ---

    // 1. Costruttore Vuoto (Richiesto dal Validator e da alcuni framework)
    public PlotObject() {
        this.metaData = new JsonObject();
    }

    // 2. Costruttore Completo
    public PlotObject(Long id, Long plotId, int x, int y, int z, String typeId, JsonObject metaData) {
        this.id = id;
        this.plotId = plotId;
        this.x = x;
        this.y = y;
        this.z = z;
        this.typeId = typeId;
        this.metaData = (metaData != null) ? metaData : new JsonObject();
    }

    // --- GETTERS & SETTERS ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getPlotId() { return plotId; }
    public void setPlotId(Long plotId) { this.plotId = plotId; }

    // Coordinate singole
    public int getX() { return x; }
    public void setX(int x) { this.x = x; }

    public int getY() { return y; }
    public void setY(int y) { this.y = y; }

    public int getZ() { return z; }
    public void setZ(int z) { this.z = z; }

    // Helper richiesto dal Validator
    public void setPosition(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    // Type ID
    public String getTypeId() { return typeId; }
    public void setTypeId(String typeId) { this.typeId = typeId; }

    // Metadata
    public JsonObject getMetaData() { return metaData; }
    public void setMetaData(JsonObject metaData) { this.metaData = metaData; }

    // --- METODI DI UTILITÀ ---

    public String getRawMetaData() {
        return this.metaData != null ? this.metaData.toString() : "{}";
    }

    public void setMetaDataFromString(String jsonString) {
        if (jsonString == null || jsonString.isEmpty()) {
            this.metaData = new JsonObject();
            return;
        }
        try {
            this.metaData = JsonParser.parseString(jsonString).getAsJsonObject();
        } catch (Exception e) {
            System.err.println("⚠️ Errore parsing JSON per PlotObject " + id + ": " + e.getMessage());
            this.metaData = new JsonObject();
        }
    }
}