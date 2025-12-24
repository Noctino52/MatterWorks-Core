package com.matterworks.core.domain.machines;

import com.google.gson.JsonObject;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.domain.matter.MatterColor;
import com.matterworks.core.domain.matter.MatterPayload;
import com.matterworks.core.domain.matter.MatterShape;

import java.util.UUID;

public class ColorMixer extends ProcessorMachine {

    public ColorMixer(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata) {
        super(dbId, ownerId, pos, typeId, metadata);
        this.dimensions = new Vector3Int(2, 1, 1);
        if (inputBuffer.getItemInSlot(0) == null) inputBuffer.insertIntoSlot(0, null);
        if (inputBuffer.getItemInSlot(1) == null) inputBuffer.insertIntoSlot(1, null);
    }

    @Override
    public boolean insertItem(MatterPayload item, GridPosition fromPos) {
        if (fromPos == null || item == null) return false;

        // RAW non entra nel mixer (non è un colore “miscelabile”)
        if (item.color() == MatterColor.RAW) return false;

        int targetSlot = getSlotForPosition(fromPos);
        if (targetSlot == -1) return false;

        if (inputBuffer.getCountInSlot(targetSlot) >= MAX_INPUT_STACK) return false;
        return insertIntoBuffer(targetSlot, item);
    }

    @Override
    protected GridPosition getOutputPosition() {
        int x = pos.x();
        int y = pos.y();
        int z = pos.z();

        // Restituisce il vicino del "Blocco Sinistro" rispetto alla faccia anteriore
        return switch (orientation) {
            case NORTH -> new GridPosition(x, y, z - 1);
            case SOUTH -> new GridPosition(x + 1, y, z + 1);
            case EAST  -> new GridPosition(x + 1, y, z);
            case WEST  -> new GridPosition(x - 1, y, z + 1);
            default -> pos;
        };
    }

    private int getSlotForPosition(GridPosition senderPos) {
        int x = pos.x();
        int y = pos.y();
        int z = pos.z();

        GridPosition slot0Pos, slot1Pos;
        switch (orientation) {
            case NORTH -> { slot0Pos = new GridPosition(x, y, z + 1); slot1Pos = new GridPosition(x + 1, y, z + 1); }
            case SOUTH -> { slot0Pos = new GridPosition(x + 1, y, z - 1); slot1Pos = new GridPosition(x, y, z - 1); }
            case EAST  -> { slot0Pos = new GridPosition(x - 1, y, z); slot1Pos = new GridPosition(x - 1, y, z + 1); }
            case WEST  -> { slot0Pos = new GridPosition(x + 1, y, z + 1); slot1Pos = new GridPosition(x + 1, y, z); }
            default -> { return -1; }
        }

        if (senderPos.equals(slot0Pos)) return 0;
        if (senderPos.equals(slot1Pos)) return 1;
        return -1;
    }

    @Override
    public void tick(long currentTick) {
        super.tryEjectItem(currentTick);

        if (currentRecipe != null) {
            if (currentTick >= finishTick) completeProcessing();
            return;
        }
        if (outputBuffer.getCount() >= MAX_OUTPUT_STACK) return;

        int count0 = inputBuffer.getCountInSlot(0);
        int count1 = inputBuffer.getCountInSlot(1);

        if (count0 > 0 && count1 > 0) {
            MatterPayload c1 = inputBuffer.getItemInSlot(0);
            MatterPayload c2 = inputBuffer.getItemInSlot(1);
            if (c1 == null || c2 == null) return;

            // stesso colore non fa nulla
            if (c1.color() == c2.color()) return;

            inputBuffer.decreaseSlot(0, 1);
            inputBuffer.decreaseSlot(1, 1);

            MatterColor mixed = MatterColor.mix(c1.color(), c2.color());

            // ✅ Safety: RAW non deve uscire mai dal mixer
            if (mixed == MatterColor.RAW) mixed = MatterColor.WHITE;

            MatterPayload result = new MatterPayload(MatterShape.SPHERE, mixed);

            this.currentRecipe = new com.matterworks.core.domain.matter.Recipe(
                    "mix_" + mixed.name(),
                    java.util.List.of(c1, c2),
                    result,
                    1.5f,
                    0
            );

            this.finishTick = currentTick + 30;
            saveState();

            System.out.println("🌀 Mixer: Mixing " + c1.color() + " + " + c2.color() + " -> " + mixed);
        }
    }
}
