package com.matterworks.core.domain.machines.base;

import com.google.gson.JsonObject;
import com.matterworks.core.common.Direction;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.managers.GridManager;
import com.matterworks.core.ports.IWorldAccess;

import java.util.Locale;
import java.util.UUID;

public abstract class PlacedMachine implements IGridComponent {

    protected Long dbId;
    protected UUID ownerId;
    protected String typeId;
    protected GridPosition pos;

    protected Direction orientation;

    protected Vector3Int dimensions;
    protected JsonObject metadata;
    protected GridManager gridManager;
    protected boolean isDirty = false;

    // Speed multiplier cache (hot path: belts/logistics call scheduleAfter a lot)
    // Use time-based TTL to keep method signatures backward compatible.
    private transient long speedCacheValidUntilMs = 0L;
    private transient double speedCacheMultiplier = 1.0;
    private static final long SPEED_CACHE_TTL_MS = 1000L; // refresh at most once per second

    public PlacedMachine(Long dbId, UUID ownerId, String typeId, GridPosition pos, JsonObject metadata) {
        this.dbId = dbId;
        this.ownerId = ownerId;
        this.typeId = typeId;
        this.pos = pos;
        this.metadata = metadata != null ? metadata : new JsonObject();

        this.orientation = Direction.NORTH;
        if (this.metadata.has("orientation")) {
            try {
                String v = this.metadata.get("orientation").getAsString();
                this.orientation = parseDirectionOrDefault(v, Direction.NORTH);
            } catch (Exception ignored) {
                this.orientation = Direction.NORTH;
            }
        }

        this.dimensions = new Vector3Int(1, 1, 1);
    }

    @Override
    public Vector3Int getDimensions() {
        if (orientation == Direction.EAST || orientation == Direction.WEST) {
            return new Vector3Int(dimensions.z(), dimensions.y(), dimensions.x());
        }
        return dimensions;
    }

    protected PlacedMachine getNeighborAt(GridPosition targetPos) {
        if (gridManager == null) return null;
        return gridManager.getMachineAt(this.ownerId, targetPos);
    }

    public void setOrientation(Direction orientation) {
        this.orientation = (orientation != null ? orientation : Direction.NORTH);
        this.metadata.addProperty("orientation", this.orientation.name());
        markDirty();
    }

    /**
     * Convenience overload for callers that pass string orientations.
     * Converts to Direction internally.
     */
    public void setOrientation(String orientation) {
        this.orientation = parseDirectionOrDefault(orientation, Direction.NORTH);
        this.metadata.addProperty("orientation", this.orientation.name());
        markDirty();
    }

    protected Direction parseDirectionOrDefault(String v, Direction def) {
        if (v == null || v.isBlank()) return def;
        try {
            return Direction.valueOf(v.toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return def;
        }
    }

    public UUID getOwnerId() { return ownerId; }
    public GridPosition getPos() { return pos; }
    public String getTypeId() { return typeId; }
    public Direction getOrientation() { return orientation; }

    public Long getDbId() { return dbId; }
    public void setDbId(Long id) { this.dbId = id; }

    public void setGridContext(GridManager gm) {
        this.gridManager = gm;
        // Reset cache when context changes
        this.speedCacheValidUntilMs = 0L;
        this.speedCacheMultiplier = 1.0;
    }

    public void markDirty() { this.isDirty = true; }
    public boolean isDirty() { return isDirty; }
    public void cleanDirty() { this.isDirty = false; }

    public abstract void tick(long currentTick);

    @Override
    public void onPlace(IWorldAccess world) { }

    @Override
    public void onRemove(IWorldAccess world) {
        onRemove();
    }

    public void onRemove() { }

    @Override
    public JsonObject serialize() {
        metadata.addProperty("orientation", orientation.name());
        return metadata;
    }

    // ==========================================================
    // Orientation helper
    // ==========================================================
    protected Vector3Int orientationToVector() {
        return orientation.toVector();
    }

    // ==========================================================
    // SPEED HELPERS (Machine speed + Overclock)  --- HOT PATH
    // ==========================================================

    /**
     * Backward-compatible signature.
     * Cached to avoid doing GridManager computation for every scheduleAfter() call.
     */
    protected double getEffectiveSpeedMultiplier() {
        if (gridManager == null) return 1.0;

        long nowMs = System.currentTimeMillis();
        if (nowMs < speedCacheValidUntilMs) {
            return speedCacheMultiplier;
        }

        double v = 1.0;
        try {
            v = gridManager.getEffectiveMachineSpeedMultiplier(ownerId, typeId);
        } catch (Throwable ignored) {
            v = 1.0;
        }

        if (Double.isNaN(v) || Double.isInfinite(v) || v <= 0.0) v = 1.0;

        // DESIGN RULE: upgrades must never slow down machines
        if (v < 1.0) v = 1.0;

        speedCacheMultiplier = v;
        speedCacheValidUntilMs = nowMs + SPEED_CACHE_TTL_MS;
        return v;
    }


    /**
     * Backward-compatible signature used by many machines.
     */
    protected long computeAcceleratedTicks(long baseTicks) {
        if (baseTicks <= 1) return Math.max(1L, baseTicks);

        double mult = getEffectiveSpeedMultiplier();
        if (mult <= 1.0) return baseTicks;

        long accelerated = (long) Math.ceil(baseTicks / mult);
        return Math.max(1L, accelerated);
    }

    protected long scheduleAfter(long currentTick, long baseTicks, String reasonForLog) {
        long effectiveTicks = computeAcceleratedTicks(baseTicks);
        return currentTick + effectiveTicks;
    }
}
