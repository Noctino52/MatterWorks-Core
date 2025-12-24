package com.matterworks.core.domain.machines.processing;

import com.google.gson.JsonObject;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.domain.machines.base.ProcessorMachine;
import com.matterworks.core.domain.matter.MatterColor;
import com.matterworks.core.domain.matter.MatterPayload;
import com.matterworks.core.domain.matter.Recipe;

import java.util.List;
import java.util.UUID;

public class Chromator extends ProcessorMachine {

    public Chromator(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata) {
        this(dbId, ownerId, pos, typeId, metadata, 64);
    }

    public Chromator(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata, int maxStackPerSlot) {
        super(dbId, ownerId, pos, typeId, metadata, maxStackPerSlot);
        this.dimensions = new Vector3Int(2, 1, 1);
    }

    @Override
    public boolean insertItem(MatterPayload item, GridPosition fromPos) {
        if (item == null || fromPos == null) return false;

        int targetSlot = getSlotForPosition(fromPos);
        if (targetSlot == -1) return false;

        boolean isDye = (item.color() != MatterColor.RAW);
        if (targetSlot == 0 && isDye) return false;
        if (targetSlot == 1 && !isDye) return false;

        return insertIntoBuffer(targetSlot, item);
    }

    @Override
    protected GridPosition getOutputPosition() {
        int x = pos.x(); int y = pos.y(); int z = pos.z();
        return switch (orientation) {
            case NORTH -> new GridPosition(x, y, z - 1);
            case SOUTH -> new GridPosition(x + 1, y, z + 1);
            case EAST -> new GridPosition(x + 1, y, z);
            case WEST -> new GridPosition(x - 1, y, z + 1);
            default -> pos;
        };
    }

    private int getSlotForPosition(GridPosition senderPos) {
        int x = pos.x(); int y = pos.y(); int z = pos.z();
        GridPosition s0, s1;
        switch (orientation) {
            case NORTH -> { s0 = new GridPosition(x, y, z + 1); s1 = new GridPosition(x + 1, y, z + 1); }
            case SOUTH -> { s0 = new GridPosition(x + 1, y, z - 1); s1 = new GridPosition(x, y, z - 1); }
            case EAST  -> { s0 = new GridPosition(x - 1, y, z); s1 = new GridPosition(x - 1, y, z + 1); }
            case WEST  -> { s0 = new GridPosition(x + 1, y, z + 1); s1 = new GridPosition(x + 1, y, z); }
            default -> { return -1; }
        }
        if (senderPos.equals(s0)) return 0;
        if (senderPos.equals(s1)) return 1;
        return -1;
    }

    @Override
    public void tick(long currentTick) {
        super.tryEjectItem(currentTick);

        if (currentRecipe != null) {
            if (currentTick >= finishTick) completeProcessing();
            return;
        }

        // output pieno -> non parte
        if (outputBuffer.getCount() >= outputBuffer.getMaxStackSize()) return;

        if (inputBuffer.getCountInSlot(0) > 0 && inputBuffer.getCountInSlot(1) > 0) {
            MatterPayload cube = inputBuffer.getItemInSlot(0);
            MatterPayload dye  = inputBuffer.getItemInSlot(1);

            if (cube == null || dye == null) return;

            inputBuffer.decreaseSlot(0, 1);
            inputBuffer.decreaseSlot(1, 1);

            MatterPayload result = new MatterPayload(cube.shape(), dye.color());
            this.currentRecipe = new Recipe("chroma_working", List.of(cube, dye), result, 1.5f, 0);
            this.finishTick = currentTick + 30;

            saveState();
        }
    }
}
