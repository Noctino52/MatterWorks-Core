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
 * ColorMixer:
 * - 2 inputs (both colors != RAW, shape == null)
 * - output: SPHERE with mixed color
 *
 * PERFORMANCE:
 * - Cached ports (no GridPosition allocations per tick/insert)
 * - No Recipe/List allocations per job
 * - Cached output by color
 */
public class ColorMixer extends ProcessorMachine {

    private static final long PROCESS_TICKS = 30;

    private transient Direction cachedOrientation;
    private transient GridPosition cachedSlot0Pos;
    private transient GridPosition cachedSlot1Pos;
    private transient GridPosition cachedOutputPos;

    private static final MatterPayload[] OUT_CACHE = new MatterPayload[MatterColor.values().length];

    public ColorMixer(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata) {
        this(dbId, ownerId, pos, typeId, metadata, 64);
    }

    public ColorMixer(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata, int maxStackPerSlot) {
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
        if (fromPos == null || item == null) return false;

        // Mixer accepts only pure colors (no shape) and != RAW
        if (item.color() == null || item.color() == MatterColor.RAW) return false;
        if (item.shape() != null) return false;

        ensurePorts();

        int targetSlot = -1;
        if (fromPos.equals(cachedSlot0Pos)) targetSlot = 0;
        else if (fromPos.equals(cachedSlot1Pos)) targetSlot = 1;
        if (targetSlot == -1) return false;

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

        MatterPayload c1 = inputBuffer.getItemInSlot(0);
        MatterPayload c2 = inputBuffer.getItemInSlot(1);
        if (c1 == null || c2 == null) return;

        if (c1.color() == null || c2.color() == null) return;
        if (c1.color() == c2.color()) return;

        consumeInput(0, 1, c1);
        consumeInput(1, 1, c2);

        MatterColor mixed = MatterColor.mix(c1.color(), c2.color());
        if (mixed == MatterColor.RAW) mixed = MatterColor.WHITE;

        MatterPayload out = cachedOutput(mixed);
        startProcessing(out, currentTick, PROCESS_TICKS, "PROCESS_START");
    }

    private static MatterPayload cachedOutput(MatterColor color) {
        if (color == null) return new MatterPayload(MatterShape.SPHERE, MatterColor.WHITE);

        int ci = color.ordinal();
        MatterPayload p = OUT_CACHE[ci];
        if (p == null) {
            p = new MatterPayload(MatterShape.SPHERE, color);
            OUT_CACHE[ci] = p;
        }
        return p;
    }
}
