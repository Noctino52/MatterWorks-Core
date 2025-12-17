package com.matterworks.core.domain.machines;

import com.google.gson.JsonObject;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.domain.matter.MatterColor;
import com.matterworks.core.domain.matter.MatterPayload;
import com.matterworks.core.domain.matter.Recipe;
import java.util.UUID;
import java.util.List;

public class Chromator extends ProcessorMachine {

    public Chromator(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata) {
        super(dbId, ownerId, pos, typeId, metadata);
        this.dimensions = new Vector3Int(2, 1, 1);
    }

    @Override
    public boolean insertItem(MatterPayload item, GridPosition fromPos) {
        if (fromPos == null) return false;
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
            case EAST ->  { s0 = new GridPosition(x - 1, y, z); s1 = new GridPosition(x - 1, y, z + 1); }
            case WEST ->  { s0 = new GridPosition(x + 1, y, z + 1); s1 = new GridPosition(x + 1, y, z); }
            default -> { return -1; }
        }
        if (senderPos.equals(s0)) return 0;
        if (senderPos.equals(s1)) return 1;
        return -1;
    }

    @Override
    public void tick(long currentTick) {
        // 1. Tenta di espellere prima di ogni altra cosa
        super.tryEjectItem(currentTick);

        // 2. Se ha finito la ricetta, sposta nel buffer di output
        if (currentRecipe != null) {
            if (currentTick >= finishTick) {
                completeProcessing();
            }
            return;
        }

        // 3. Se il buffer di output è pieno, non iniziare nulla
        if (outputBuffer.getCount() >= MAX_OUTPUT_STACK) return;

        // 4. Se ha gli ingredienti (Slot 0: Cubo, Slot 1: Colore), avvia ricetta
        if (inputBuffer.getCountInSlot(0) > 0 && inputBuffer.getCountInSlot(1) > 0) {
            MatterPayload cube = inputBuffer.getItemInSlot(0);
            MatterPayload dye = inputBuffer.getItemInSlot(1);

            inputBuffer.decreaseSlot(0, 1);
            inputBuffer.decreaseSlot(1, 1);

            MatterPayload result = new MatterPayload(cube.shape(), dye.color());
            this.currentRecipe = new Recipe("chroma_working", List.of(cube, dye), result, 1.5f, 0);
            this.finishTick = currentTick + 30; // 1.5 sec

            saveState();
        }
    }
}