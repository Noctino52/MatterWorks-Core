package com.matterworks.core.domain.machines;

import com.google.gson.JsonObject;
import com.matterworks.core.common.Direction;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.managers.GridManager;
import com.matterworks.core.ports.IWorldAccess;

import java.util.UUID;

public abstract class PlacedMachine implements IGridComponent {

    protected Long dbId;
    protected UUID ownerId;
    protected String typeId;
    protected GridPosition pos;
    protected Direction orientation;
    protected Vector3Int dimensions; // Dimensioni BASE
    protected JsonObject metadata;
    protected GridManager gridManager;
    protected boolean isDirty = false;

    public PlacedMachine(Long dbId, UUID ownerId, String typeId, GridPosition pos, JsonObject metadata) {
        this.dbId = dbId;
        this.ownerId = ownerId;
        this.typeId = typeId;
        this.pos = pos;
        this.metadata = metadata != null ? metadata : new JsonObject();

        this.orientation = Direction.NORTH;
        if (this.metadata.has("orientation")) {
            this.orientation = Direction.valueOf(this.metadata.get("orientation").getAsString());
        }

        this.dimensions = new Vector3Int(1, 1, 1);
    }

    // --- FIX ROTAZIONE FISICA ---
    @Override
    public Vector3Int getDimensions() {
        if (orientation == Direction.EAST || orientation == Direction.WEST) {
            return new Vector3Int(dimensions.z(), dimensions.y(), dimensions.x());
        }
        return dimensions;
    }

    public void setOrientation(Direction orientation) {
        this.orientation = orientation;
        this.metadata.addProperty("orientation", orientation.name());
        markDirty();
    }

    // --- METODI MANCANTI AGGIUNTI QUI ---
    public UUID getOwnerId() { return ownerId; }
    public void cleanDirty() { this.isDirty = false; }

    // --- METODI STANDARD ---
    public GridPosition getPos() { return pos; }
    public String getTypeId() { return typeId; }
    public Direction getOrientation() { return orientation; }

    public Long getDbId() { return dbId; }
    public void setDbId(Long id) { this.dbId = id; }

    public void setGridContext(GridManager gm) { this.gridManager = gm; }

    public void markDirty() { this.isDirty = true; }
    public boolean isDirty() { return isDirty; }

    public abstract void tick(long currentTick);

    public void onPlace(IWorldAccess world) {}
    public void onRemove() {}

    public JsonObject serialize() {
        metadata.addProperty("orientation", orientation.name());
        return metadata;
    }
}