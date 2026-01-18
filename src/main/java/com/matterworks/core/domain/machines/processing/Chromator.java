package com.matterworks.core.domain.machines.processing;

import com.google.gson.JsonObject;
import com.matterworks.core.common.Direction;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.domain.machines.base.PlacedMachine;
import com.matterworks.core.domain.machines.base.ProcessorMachine;
import com.matterworks.core.domain.matter.MatterColor;
import com.matterworks.core.domain.matter.MatterPayload;
import com.matterworks.core.domain.matter.Recipe;

import java.util.List;
import java.util.UUID;

public class Chromator extends ProcessorMachine {

    private static final long PROCESS_TICKS = 40;

    // Target color
    private MatterColor targetColor = MatterColor.RED;

    // Cached ports (DO NOT use neighbor lookups; Chromator is 1x1 so ports are stable)
    private transient GridPosition cachedInputPort;
    private transient GridPosition cachedOutputPort;
    private transient Direction cachedOrientationForPorts;

    // Cached recipe/output payload for current target color
    private transient MatterColor cachedColorForPayload;
    private transient MatterPayload cachedOutputPayload;

    public Chromator(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata) {
        this(dbId, ownerId, pos, typeId, metadata, 64);
    }

    public Chromator(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata, int maxStackPerSlot) {
        super(dbId, ownerId, pos, typeId, metadata, maxStackPerSlot);
        this.dimensions = Vector3Int.one();

        if (this.metadata != null && this.metadata.has("targetColor")) {
            try {
                this.targetColor = MatterColor.valueOf(this.metadata.get("targetColor").getAsString());
            } catch (Throwable ignored) {
                this.targetColor = MatterColor.RED;
            }
        }

        recomputePorts();
        refreshCachedOutputPayload();
    }

    @Override
    public void setOrientation(Direction orientation) {
        super.setOrientation(orientation);
        recomputePorts();
    }

    @Override
    public void setOrientation(String orientation) {
        super.setOrientation(orientation);
        recomputePorts();
    }

    private void recomputePorts() {
        Vector3Int f = orientationToVector();
        Vector3Int back = new Vector3Int(-f.x(), -f.y(), -f.z());

        cachedOutputPort = new GridPosition(pos.x() + f.x(), pos.y() + f.y(), pos.z() + f.z());
        cachedInputPort  = new GridPosition(pos.x() + back.x(), pos.y() + back.y(), pos.z() + back.z());
        cachedOrientationForPorts = orientation;
    }

    private void ensurePorts() {
        if (cachedOrientationForPorts != orientation || cachedInputPort == null || cachedOutputPort == null) {
            recomputePorts();
        }
    }

    private void refreshCachedOutputPayload() {
        if (cachedColorForPayload == targetColor && cachedOutputPayload != null) return;
        cachedColorForPayload = targetColor;
        // Preserve shape/effects from input, but output color is targetColor.
        // We'll apply it when starting recipe (we only need a "template" here).
        cachedOutputPayload = null;
    }

    public void setTargetColor(MatterColor c) {
        if (c == null) return;
        if (this.targetColor == c) return;

        this.targetColor = c;
        if (metadata == null) metadata = new JsonObject();
        metadata.addProperty("targetColor", targetColor.name());
        refreshCachedOutputPayload();
        markDirty();
    }

    @Override
    protected GridPosition getOutputPosition() {
        ensurePorts();
        return cachedOutputPort;
    }

    private GridPosition getInputPortPosition() {
        ensurePorts();
        return cachedInputPort;
    }

    @Override
    public boolean insertItem(MatterPayload item, GridPosition fromPos) {
        if (item == null || fromPos == null) return false;

        // Accept any matter payload (coloring step)
        return fromPos.equals(getInputPortPosition()) && insertIntoBuffer(0, item);
    }

    @Override
    public void tick(long currentTick) {
        super.tryEjectItem(currentTick);

        if (currentRecipe != null) {
            if (currentTick >= finishTick) completeProcessing();
            return;
        }

        if (outputBuffer.getCount() >= outputBuffer.getMaxStackSize()) return;
        if (inputBuffer.getCountInSlot(0) <= 0) return;

        MatterPayload in = inputBuffer.getItemInSlot(0);
        if (in == null) return;

        consumeInput(0, 1, in);

        MatterPayload out = new MatterPayload(in.shape(), targetColor, in.effects());

        // Minimal recipe object, no lookups
        this.currentRecipe = new Recipe("chromator_recolor", List.of(in), out, 2.0f, 0);

        this.finishTick = scheduleAfter(currentTick, PROCESS_TICKS, "PROCESS_START");
        saveState();
    }

    @Override
    public JsonObject serialize() {
        if (metadata == null) metadata = new JsonObject();
        metadata.addProperty("targetColor", targetColor.name());
        return super.serialize();
    }
}
