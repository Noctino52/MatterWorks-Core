package com.matterworks.core.domain.machines.processing;

import com.google.gson.JsonObject;
import com.matterworks.core.common.Direction;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.domain.machines.base.ProcessorMachine;
import com.matterworks.core.domain.matter.MatterColor;
import com.matterworks.core.domain.matter.MatterPayload;
import com.matterworks.core.domain.matter.MatterShape;

import java.util.UUID;

/**
 * Chromator:
 * - Slot 0: base matter (NOT a dye -> color == RAW)
 * - Slot 1: dye (color != RAW and shape == null)
 * Output: base shape recolored with dye color (effects not copied).
 *
 * PERFORMANCE:
 * - No Recipe/List allocations per job.
 * - Cached ports.
 * - Cached output MatterPayload by (shape,color).
 */
public class Chromator extends ProcessorMachine {

    private static final long PROCESS_TICKS = 30;

    // Cached ports (depend on pos + orientation)
    private transient Direction cachedOrientation;
    private transient GridPosition cachedSlot0Pos;
    private transient GridPosition cachedSlot1Pos;
    private transient GridPosition cachedOutputPos;

    // Output cache [shapeOrdinal][colorOrdinal]
    private static final MatterPayload[][] OUT_CACHE =
            new MatterPayload[MatterShape.values().length][MatterColor.values().length];

    public Chromator(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata) {
        this(dbId, ownerId, pos, typeId, metadata, 64);
    }

    public Chromator(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata, int maxStackPerSlot) {
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
        if (cachedOrientation != orientation || cachedSlot0Pos == null || cachedSlot1Pos == null || cachedOutputPos == null) {
            recomputePorts();
        }
    }

    private void recomputePorts() {
        int x = pos.x();
        int y = pos.y();
        int z = pos.z();

        GridPosition s0, s1, out;

        switch (orientation) {
            case NORTH -> {
                s0 = new GridPosition(x,     y, z + 1);
                s1 = new GridPosition(x + 1, y, z + 1);
                out = new GridPosition(x, y, z - 1);
            }
            case SOUTH -> {
                s0 = new GridPosition(x + 1, y, z - 1);
                s1 = new GridPosition(x,     y, z - 1);
                out = new GridPosition(x + 1, y, z + 1);
            }
            case EAST -> {
                s0 = new GridPosition(x - 1, y, z);
                s1 = new GridPosition(x - 1, y, z + 1);
                out = new GridPosition(x + 1, y, z);
            }
            case WEST -> {
                s0 = new GridPosition(x + 1, y, z + 1);
                s1 = new GridPosition(x + 1, y, z);
                out = new GridPosition(x - 1, y, z + 1);
            }
            default -> {
                s0 = pos;
                s1 = pos;
                out = pos;
            }
        }

        cachedSlot0Pos = s0;
        cachedSlot1Pos = s1;
        cachedOutputPos = out;
        cachedOrientation = orientation;
    }

    @Override
    public boolean insertItem(MatterPayload item, GridPosition fromPos) {
        if (item == null || fromPos == null) return false;

        ensurePorts();

        int targetSlot = -1;
        if (fromPos.equals(cachedSlot0Pos)) targetSlot = 0;
        else if (fromPos.equals(cachedSlot1Pos)) targetSlot = 1;
        if (targetSlot == -1) return false;

        boolean isDye = (item.color() != null && item.color() != MatterColor.RAW);

        // Slot constraints
        if (targetSlot == 0 && isDye) return false;
        if (targetSlot == 1 && !isDye) return false;

        // Extra constraint: dye must be "only color" (no shape)
        if (targetSlot == 1 && item.shape() != null) return false;

        return insertIntoBuffer(targetSlot, item);
    }

    @Override
    protected GridPosition getOutputPosition() {
        ensurePorts();
        return cachedOutputPos;
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
        if (inputBuffer.getCountInSlot(1) <= 0) return;

        MatterPayload base = inputBuffer.getItemInSlot(0);
        MatterPayload dye  = inputBuffer.getItemInSlot(1);
        if (base == null || dye == null) return;

        // dye must be a color != RAW, base must be RAW-colored
        if (dye.color() == null || dye.color() == MatterColor.RAW) return;
        if (base.color() != null && base.color() != MatterColor.RAW) return;
        if (base.shape() == null) return;

        consumeInput(0, 1, base);
        consumeInput(1, 1, dye);

        MatterPayload out = cachedOutput(base.shape(), dye.color());
        startProcessing(out, currentTick, PROCESS_TICKS, "PROCESS_START");
    }

    private static MatterPayload cachedOutput(MatterShape shape, MatterColor color) {
        if (shape == null || color == null) return new MatterPayload(shape, color);

        int si = shape.ordinal();
        int ci = color.ordinal();

        MatterPayload p = OUT_CACHE[si][ci];
        if (p == null) {
            p = new MatterPayload(shape, color);
            OUT_CACHE[si][ci] = p;
        }
        return p;
    }
}
