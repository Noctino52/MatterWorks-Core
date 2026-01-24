package com.matterworks.core.domain.machines.processing;

import com.google.gson.JsonObject;
import com.matterworks.core.common.Direction;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.domain.machines.base.PlacedMachine;
import com.matterworks.core.domain.machines.base.ProcessorMachine;
import com.matterworks.core.domain.matter.MatterPayload;
import com.matterworks.core.domain.matter.MatterShape;

import java.util.UUID;

/**
 * ShaperMachine:
 * - Input: CUBE
 * - Output: SPHERE (same color/effects)
 *
 * PERFORMANCE:
 * - Cached input/output ports (no stepOutOfSelf() allocations per tick)
 * - No Recipe/List allocations per job (uses ProcessorMachine.startProcessing())
 */
public class ShaperMachine extends ProcessorMachine {

    private static final long PROCESS_TICKS = 40;

    private transient Direction cachedOrientation;
    private transient GridPosition cachedInputPos;
    private transient GridPosition cachedOutputPos;

    public ShaperMachine(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata) {
        this(dbId, ownerId, pos, typeId, metadata, 64);
    }

    public ShaperMachine(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata, int maxStackPerSlot) {
        super(dbId, ownerId, pos, typeId, metadata, maxStackPerSlot);
        this.dimensions = new Vector3Int(2, 1, 1);
        recomputePorts();
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

    private void ensurePorts() {
        if (cachedOrientation != orientation || cachedInputPos == null || cachedOutputPos == null) {
            recomputePorts();
        }
    }

    private GridPosition stepOutOfSelf(GridPosition start, Vector3Int step) {
        GridPosition p = start;
        for (int i = 0; i < 3; i++) {
            PlacedMachine n = getNeighborAt(p);
            if (n == null || n != this) return p;
            p = new GridPosition(p.x() + step.x(), p.y() + step.y(), p.z() + step.z());
        }
        return p;
    }

    private void recomputePorts() {
        Vector3Int f = orientationToVector();
        Vector3Int back = new Vector3Int(-f.x(), -f.y(), -f.z());

        GridPosition inStart = new GridPosition(pos.x() + back.x(), pos.y() + back.y(), pos.z() + back.z());
        GridPosition outStart = new GridPosition(pos.x() + f.x(), pos.y() + f.y(), pos.z() + f.z());

        cachedInputPos = stepOutOfSelf(inStart, back);
        cachedOutputPos = stepOutOfSelf(outStart, f);

        cachedOrientation = orientation;
    }

    @Override
    protected GridPosition getOutputPosition() {
        ensurePorts();
        return cachedOutputPos;
    }

    @Override
    public boolean insertItem(MatterPayload item, GridPosition fromPos) {
        if (item == null || fromPos == null) return false;
        if (item.shape() != MatterShape.CUBE) return false;

        ensurePorts();
        return fromPos.equals(cachedInputPos) && insertIntoBuffer(0, item);
    }

    @Override
    public void tick(long currentTick) {
        super.tryEjectItem(currentTick);

        if (isProcessing()) {
            if (currentTick >= finishTick) completeProcessing();
            return;
        }

        if (outputBuffer.getCount() >= outputBuffer.getMaxStackSize()) return;
        if (inputBuffer.getCountInSlot(0) <= 0) return;

        MatterPayload in = inputBuffer.getItemInSlot(0);
        if (in == null || in.shape() != MatterShape.CUBE) return;

        consumeInput(0, 1, in);

        // No Recipe/List allocations:
        MatterPayload out = new MatterPayload(MatterShape.SPHERE, in.color(), in.effects());
        startProcessing(out, currentTick, PROCESS_TICKS, "PROCESS_START");
    }
}
