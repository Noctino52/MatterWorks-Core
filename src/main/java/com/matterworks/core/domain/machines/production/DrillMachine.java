package com.matterworks.core.domain.machines.production;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.matterworks.core.common.Direction;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.domain.machines.base.PlacedMachine;
import com.matterworks.core.domain.machines.logistics.ConveyorBelt;
import com.matterworks.core.domain.matter.MatterColor;
import com.matterworks.core.domain.matter.MatterPayload;
import com.matterworks.core.domain.matter.MatterShape;

import java.util.UUID;

public class DrillMachine extends PlacedMachine {

    private long nextSpawnTick = -1;

    private final int tierLevel;
    private final float productionSpeed;
    private final int maxStackSize;

    // Runtime state (NO JSON churn per tick)
    private int outputCount = 0;
    private MatterColor resourceToMine = MatterColor.RAW;

    private transient MatterPayload cachedOutputPayload;

    private transient Vector3Int cachedDir;
    private transient GridPosition cachedOutPos;

    private transient long neighborCacheValidUntilTick = Long.MIN_VALUE;
    private transient PlacedMachine cachedNeighbor = null;
    private static final long NEIGHBOR_CACHE_TTL_TICKS = 20L;

    // Small spread to avoid all drills spawning on the exact same tick (ONLY for initial schedule)
    private final int spawnPhaseSeed;

    public DrillMachine(Long dbId,
                        UUID ownerId,
                        GridPosition pos,
                        String typeId,
                        JsonObject metadata,
                        int tierLevel,
                        int maxStackPerSlot) {
        super(dbId, ownerId, typeId, pos, metadata);

        this.dimensions = Vector3Int.one();
        this.tierLevel = tierLevel;

        this.maxStackSize = Math.max(1, maxStackPerSlot);

        if (this.metadata != null && this.metadata.has("mining_resource")) {
            try {
                this.resourceToMine = MatterColor.valueOf(this.metadata.get("mining_resource").getAsString());
            } catch (Throwable ignored) {
                this.resourceToMine = MatterColor.RAW;
            }
        }

        if (this.metadata != null && this.metadata.has("items") && this.metadata.get("items").isJsonArray()) {
            try {
                JsonArray arr = this.metadata.getAsJsonArray("items");
                if (arr.size() > 0 && arr.get(0).isJsonObject()) {
                    JsonObject slot0 = arr.get(0).getAsJsonObject();
                    int c = slot0.has("count") ? slot0.get("count").getAsInt() : 0;
                    this.outputCount = Math.max(0, Math.min(c, this.maxStackSize));

                    if ((this.metadata == null || !this.metadata.has("mining_resource")) && slot0.has("color")) {
                        try {
                            this.resourceToMine = MatterColor.valueOf(slot0.get("color").getAsString());
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {
                this.outputCount = 0;
            }
        }

        this.productionSpeed = switch (tierLevel) {
            case 1 -> 1.0f;
            case 2 -> 2.0f;
            case 3 -> 4.0f;
            default -> 1.0f;
        };

        this.spawnPhaseSeed = mixPos(pos);

        refreshCachedPayload();
        recomputeCachedOutput();
    }

    private static int mixPos(GridPosition p) {
        int x = p.x() * 73856093;
        int y = p.y() * 19349663;
        int z = p.z() * 83492791;
        return x ^ y ^ z;
    }

    private void refreshCachedPayload() {
        MatterShape shape = (this.resourceToMine == MatterColor.RAW) ? MatterShape.CUBE : null;
        this.cachedOutputPayload = new MatterPayload(shape, this.resourceToMine);
    }

    private void recomputeCachedOutput() {
        this.cachedDir = orientationToVector();
        this.cachedOutPos = new GridPosition(
                pos.x() + cachedDir.x(),
                pos.y() + cachedDir.y(),
                pos.z() + cachedDir.z()
        );

        this.neighborCacheValidUntilTick = Long.MIN_VALUE;
        this.cachedNeighbor = null;
    }

    @Override
    public void setOrientation(Direction orientation) {
        super.setOrientation(orientation);
        recomputeCachedOutput();
    }

    @Override
    public void setOrientation(String orientation) {
        super.setOrientation(orientation);
        recomputeCachedOutput();
    }

    public void setResourceToMine(MatterColor c) {
        if (c == null) return;
        if (this.resourceToMine == c) return;

        this.resourceToMine = c;
        refreshCachedPayload();
        markDirty();
    }

    @Override
    public void tick(long currentTick) {
        long baseInterval = (long) (20 / productionSpeed);
        if (baseInterval < 1) baseInterval = 1;

        // Phase offset ONLY once (initial schedule), not every spawn
        if (nextSpawnTick == -1) {
            nextSpawnTick = scheduleAfter(currentTick, baseInterval, "PROD_SPAWN") + phaseForInterval(baseInterval);
        }

        if (currentTick >= nextSpawnTick) {
            if (outputCount < maxStackSize) {
                outputCount++;

                try {
                    if (gridManager != null && gridManager.getProductionTelemetry() != null) {
                        gridManager.getProductionTelemetry().recordProduced(getOwnerId(), cachedOutputPayload, 1L);
                    }
                } catch (Throwable ignored) {}

                markDirty();
            }

            // Next cycles: no phase add (keep stable cadence)
            nextSpawnTick = scheduleAfter(currentTick, baseInterval, "PROD_SPAWN");
        }

        tryEjectItem(currentTick);
    }

    private int phaseForInterval(long interval) {
        if (interval <= 1) return 0;
        int m = (spawnPhaseSeed & 0x7fffffff) % (int) interval;
        return (m < 0) ? 0 : m;
    }

    private PlacedMachine getNeighborCached(long currentTick) {
        if (currentTick < neighborCacheValidUntilTick) return cachedNeighbor;

        PlacedMachine n = getNeighborAt(cachedOutPos);
        cachedNeighbor = n;
        neighborCacheValidUntilTick = currentTick + NEIGHBOR_CACHE_TTL_TICKS;
        return n;
    }

    private void tryEjectItem(long currentTick) {
        if (outputCount <= 0) return;

        PlacedMachine neighbor = getNeighborCached(currentTick);
        if (neighbor instanceof ConveyorBelt belt) {
            if (belt.insertItem(cachedOutputPayload, currentTick)) {
                outputCount--;
                markDirty();
            }
        }
    }

    @Override
    public JsonObject serialize() {
        if (metadata == null) metadata = new JsonObject();

        metadata.addProperty("mining_resource", resourceToMine.name());
        metadata.addProperty("capacity", 1);

        JsonArray items = new JsonArray();
        if (outputCount > 0) {
            JsonObject slot0 = cachedOutputPayload.serialize();
            slot0.addProperty("count", Math.max(1, Math.min(outputCount, maxStackSize)));
            items.add(slot0);
        } else {
            items.add(JsonNull.INSTANCE);
        }
        metadata.add("items", items);

        return super.serialize();
    }
}
