package com.matterworks.core.domain.machines;

import com.google.gson.JsonObject;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.domain.matter.MatterColor;
import com.matterworks.core.domain.matter.MatterPayload;

import java.util.UUID;

public class Chromator extends ProcessorMachine {

    public Chromator(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata) {
        super(dbId, ownerId, pos, typeId, metadata);
        this.dimensions = new Vector3Int(2, 1, 1);
        if (inputBuffer.getItemInSlot(0) == null) inputBuffer.insertIntoSlot(0, null);
        if (inputBuffer.getItemInSlot(1) == null) inputBuffer.insertIntoSlot(1, null);
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

    // --- FIX OUTPUT POSITION ---
    @Override
    protected GridPosition getOutputPosition() {
        int x = pos.x();
        int y = pos.y();
        int z = pos.z();

        // Restituisce il vicino del "Blocco Sinistro" rispetto alla faccia anteriore
        return switch (orientation) {
            // NORD: Fronte è Z-min. Sinistra è X (Ovest). Output deve essere a (x, z-1).
            case NORTH -> new GridPosition(x, y, z - 1);

            // SUD: Fronte è Z-max. Sinistra è X+1 (Est). Output deve essere a (x+1, z+1).
            case SOUTH -> new GridPosition(x + 1, y, z + 1);

            // EST: Fronte è X-max. Sinistra è Z (Nord). Output deve essere a (x+1, z).
            case EAST -> new GridPosition(x + 1, y, z);

            // OVEST: Fronte è X-min. Sinistra è Z+1 (Sud). Output deve essere a (x-1, z+1).
            case WEST -> new GridPosition(x - 1, y, z + 1);

            default -> pos;
        };
    }

    private int getSlotForPosition(GridPosition senderPos) {
        // ... (Stessa logica di prima) ...
        int x = pos.x(); int y = pos.y(); int z = pos.z();
        GridPosition slot0Pos = null, slot1Pos = null;
        switch (orientation) {
            case NORTH: slot0Pos = new GridPosition(x, y, z + 1); slot1Pos = new GridPosition(x + 1, y, z + 1); break;
            case SOUTH: slot0Pos = new GridPosition(x + 1, y, z - 1); slot1Pos = new GridPosition(x, y, z - 1); break;
            case EAST:  slot0Pos = new GridPosition(x - 1, y, z); slot1Pos = new GridPosition(x - 1, y, z + 1); break;
            case WEST:  slot0Pos = new GridPosition(x + 1, y, z + 1); slot1Pos = new GridPosition(x + 1, y, z); break;
            default: return -1;
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

        int countShape = inputBuffer.getCountInSlot(0);
        int countDye = inputBuffer.getCountInSlot(1);

        if (countShape > 0 && countDye > 0) {
            MatterPayload inputShape = inputBuffer.getItemInSlot(0);
            MatterPayload inputDye = inputBuffer.getItemInSlot(1);
            inputBuffer.decreaseSlot(0, 1);
            inputBuffer.decreaseSlot(1, 1);
            MatterPayload result = new MatterPayload(inputShape.shape(), inputDye.color());
            this.currentRecipe = new com.matterworks.core.domain.matter.Recipe("chroma_op", java.util.List.of(inputShape, inputDye), result, 2.0f, 0);
            this.finishTick = currentTick + 40;
            saveState();
            System.out.println("🎨 Chromator: Mix " + inputShape.shape() + " + " + inputDye.color());
        }
    }
}