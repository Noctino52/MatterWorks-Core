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

/**
 * Chromator:
 * - Slot 0: "base" = Matter con shape != null e color == RAW
 * - Slot 1: "dye"  = SOLO COLORE (shape == null) e color != RAW
 * Output: stessa shape della base + colore del dye
 */
public class Chromator extends ProcessorMachine {

    public Chromator(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata) {
        super(dbId, ownerId, pos, typeId, metadata);
        this.dimensions = new Vector3Int(2, 1, 1);
    }

    @Override
    public boolean insertItem(MatterPayload item, GridPosition fromPos) {
        if (item == null || fromPos == null) return false;

        int targetSlot = getSlotForPosition(fromPos);
        if (targetSlot == -1) return false;

        // ✅ Validazioni forti per evitare "dye" con shape
        if (targetSlot == 0) {
            // base: deve avere shape e deve essere RAW
            if (item.shape() == null) return false;
            if (item.color() != MatterColor.RAW) return false;
        } else if (targetSlot == 1) {
            // dye: deve essere SOLO colore (shape null) e non RAW
            if (item.shape() != null) return false;
            if (item.color() == MatterColor.RAW) return false;
        }

        return insertIntoBuffer(targetSlot, item);
    }

    @Override
    protected GridPosition getOutputPosition() {
        int x = pos.x();
        int y = pos.y();
        int z = pos.z();
        return switch (orientation) {
            case NORTH -> new GridPosition(x, y, z - 1);
            case SOUTH -> new GridPosition(x + 1, y, z + 1);
            case EAST -> new GridPosition(x + 1, y, z);
            case WEST -> new GridPosition(x - 1, y, z + 1);
            default -> pos;
        };
    }

    private int getSlotForPosition(GridPosition senderPos) {
        int x = pos.x();
        int y = pos.y();
        int z = pos.z();

        GridPosition s0, s1;
        switch (orientation) {
            case NORTH -> { s0 = new GridPosition(x, y, z + 1); s1 = new GridPosition(x + 1, y, z + 1); }
            case SOUTH -> { s0 = new GridPosition(x + 1, y, z - 1); s1 = new GridPosition(x, y, z - 1); }
            case EAST ->  { s0 = new GridPosition(x - 1, y, z);     s1 = new GridPosition(x - 1, y, z + 1); }
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

        // 4. Se ha gli ingredienti (Slot 0: Base, Slot 1: Dye), avvia ricetta
        if (inputBuffer.getCountInSlot(0) > 0 && inputBuffer.getCountInSlot(1) > 0) {
            MatterPayload base = inputBuffer.getItemInSlot(0);
            MatterPayload dye = inputBuffer.getItemInSlot(1);
            if (base == null || dye == null) return;

            // ✅ Safety (anche per vecchi salvataggi): rispetta i vincoli
            if (base.shape() == null) return;
            if (base.color() != MatterColor.RAW) return;
            if (dye.shape() != null) return;
            if (dye.color() == MatterColor.RAW) return;

            inputBuffer.decreaseSlot(0, 1);
            inputBuffer.decreaseSlot(1, 1);

            MatterPayload result = new MatterPayload(base.shape(), dye.color());
            this.currentRecipe = new Recipe("chroma_working", List.of(base, dye), result, 1.5f, 0);
            this.finishTick = currentTick + 30; // 1.5 sec

            saveState();
        }
    }
}
