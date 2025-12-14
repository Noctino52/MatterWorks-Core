package com.matterworks.core.domain.machines;

import com.google.gson.JsonObject;
import com.matterworks.core.common.Direction;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.managers.GridManager;
import com.matterworks.core.ports.IMachineVisuals;
import com.matterworks.core.ports.IWorldAccess;

import java.util.UUID;

public abstract class PlacedMachine implements IGridComponent {

    protected final UUID instanceId;
    protected final Long dbId;
    protected final UUID ownerId;
    protected final GridPosition pos;
    protected final String typeId;

    // Logistica
    protected Direction orientation;
    protected GridManager gridManager;

    // Stato
    protected JsonObject metadata;
    protected boolean isDirty;
    protected Vector3Int dimensions;

    // Riferimenti
    protected IWorldAccess worldRef;
    protected IMachineVisuals visuals;

    public PlacedMachine(Long dbId, UUID ownerId, String typeId, GridPosition pos, JsonObject metadata) {
        this.dbId = dbId;
        this.instanceId = UUID.randomUUID();
        this.ownerId = ownerId;
        this.typeId = typeId;
        this.pos = pos;
        this.metadata = (metadata != null) ? metadata : new JsonObject();
        this.isDirty = false; // Appena caricato è pulito
        this.dimensions = Vector3Int.one();

        // Caricamento Orientamento
        if (this.metadata.has("orientation")) {
            this.orientation = Direction.valueOf(this.metadata.get("orientation").getAsString());
        } else {
            this.orientation = Direction.NORTH;
        }
    }

    public void setGridContext(GridManager gridManager) {
        this.gridManager = gridManager;
    }

    public abstract void tick(long currentTick);

    @Override
    public void onPlace(IWorldAccess world) {
        this.worldRef = world;
        markDirty();
    }

    @Override
    public void onRemove() {}

    @Override
    public JsonObject serialize() {
        this.metadata.addProperty("orientation", orientation.name());
        return this.metadata;
    }

    @Override
    public Vector3Int getDimensions() { return this.dimensions; }

    // --- GESTIONE STATO "DIRTY" (Per il GridSaverService) ---

    protected void markDirty() {
        this.isDirty = true;
    }

    // Metodo PUBBLICO richiesto dal GridSaverService
    public boolean isDirty() {
        return isDirty;
    }

    // Metodo PUBBLICO richiesto dal GridSaverService
    public void clearDirty() {
        this.isDirty = false;
    }

    // --- GETTERS (Richiesti dal GridSaverService e altri) ---

    public UUID getOwnerId() { return ownerId; } // <--- ERA MANCANTE
    public GridPosition getPos() { return pos; }
    public Direction getOrientation() { return orientation; }

    public void setOrientation(Direction dir) {
        this.orientation = dir;
        markDirty();
    }

    public String getTypeId() { return typeId; }
    public Long getDbId() { return dbId; }
}