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
import com.matterworks.core.synchronization.SimulationTime;

import java.util.UUID;

/**
 * Drill is data-driven:
 * - Machine typeId is always "drill"
 * - Upgrades (DB speed, tech tree, overclocks) are applied via getEffectiveSpeedMultiplier()
 *
 * Baseline: speedMult=1.0 => 1 item/sec => 60 items/min.
 */
public class DrillMachine extends PlacedMachine {

    private static final double BASE_ITEMS_PER_SECOND = 1.0;

    private final int maxStackSize;

    // dt-based accumulator in "items"
    private double spawnAccumulator = 0.0;

    // Output buffer (single slot with count)
    private int outputCount = 0;

    private MatterColor resourceToMine = MatterColor.RAW;
    private transient MatterPayload cachedOutputPayload;

    private transient Vector3Int cachedDir;
    private transient GridPosition cachedOutPos;

    private transient long neighborCacheValidUntilTick = Long.MIN_VALUE;
    private transient PlacedMachine cachedNeighbor = null;
    private static final long NEIGHBOR_CACHE_TTL_TICKS = 20L;

    public DrillMachine(Long dbId,
                        UUID ownerId,
                        GridPosition pos,
                        String typeId,
                        JsonObject metadata,
                        int maxStackPerSlot) {
        super(dbId, ownerId, typeId, pos, metadata);

        // Drill is 1x2x1
        this.dimensions = new Vector3Int(1, 2, 1);

        this.maxStackSize = Math.max(1, maxStackPerSlot);

        if (this.metadata != null && this.metadata.has("mining_resource")) {
            try {
                this.resourceToMine = MatterColor.valueOf(this.metadata.get("mining_resource").getAsString());
            } catch (Throwable ignored) {
                this.resourceToMine = MatterColor.RAW;
            }
        }

        if (this.metadata != null && this.metadata.has("spawn_acc") && this.metadata.get("spawn_acc").isJsonPrimitive()) {
            try {
                this.spawnAccumulator = this.metadata.get("spawn_acc").getAsDouble();
                if (Double.isNaN(this.spawnAccumulator) || Double.isInfinite(this.spawnAccumulator) || this.spawnAccumulator < 0.0) {
                    this.spawnAccumulator = 0.0;
                }
                if (this.spawnAccumulator > 5.0) this.spawnAccumulator = 5.0;
            } catch (Throwable ignored) {
                this.spawnAccumulator = 0.0;
            }
        }

        if (this.metadata != null && this.metadata.has("items") && this.metadata.get("items").isJsonArray()) {
            try {
                JsonArray arr = this.metadata.getAsJsonArray("items");
                if (arr.size() > 0 && arr.get(0).isJsonObject()) {
                    JsonObject slot0 = arr.get(0).getAsJsonObject();
                    int c = slot0.has("count") ? slot0.get("count").getAsInt() : 0;
                    this.outputCount = Math.max(0, Math.min(c, this.maxStackSize));

                    if ((!this.metadata.has("mining_resource")) && slot0.has("color")) {
                        try {
                            this.resourceToMine = MatterColor.valueOf(slot0.get("color").getAsString());
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {
                this.outputCount = 0;
            }
        }

        refreshCachedPayload();
        recomputeCachedOutput();
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
        double dt = SimulationTime.getDtSeconds();
        if (dt <= 0.0) dt = 0.05;

        double speedMult = getEffectiveSpeedMultiplier();
        if (speedMult <= 0.0) speedMult = 1.0;

        double rate = BASE_ITEMS_PER_SECOND * speedMult;
        spawnAccumulator += dt * rate;

        final int MAX_BURST_PER_TICK = 8;

        int produced = 0;
        while (spawnAccumulator >= 1.0 && produced < MAX_BURST_PER_TICK && outputCount < maxStackSize) {
            outputCount++;
            spawnAccumulator -= 1.0;
            produced++;

            try {
                if (gridManager != null && gridManager.getProductionTelemetry() != null) {
                    gridManager.getProductionTelemetry().recordProduced(getOwnerId(), cachedOutputPayload, 1L);
                }
            } catch (Throwable ignored) {}

            markDirty();
        }

        if (outputCount >= maxStackSize && spawnAccumulator > 2.0) {
            spawnAccumulator = 2.0;
        }

        tryEjectItem(currentTick);
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
        metadata.addProperty("spawn_acc", Math.max(0.0, Math.min(spawnAccumulator, 5.0)));

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
