package com.matterworks.core.domain.machines;

import com.google.gson.JsonObject;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.domain.matter.MatterPayload;
import com.matterworks.core.domain.matter.MatterShape;
import com.matterworks.core.domain.matter.Recipe;

import java.util.List;
import java.util.UUID;

/**
 * DB typeId: "smoothing"
 * Trasforma: CUBE -> SPHERE (mantiene colore + effetti)
 */
public class ShaperMachine extends ProcessorMachine {

    private static final long PROCESS_TICKS = 40; // ~2s

    public ShaperMachine(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata) {
        super(dbId, ownerId, pos, typeId, metadata);
        this.dimensions = new Vector3Int(2, 1, 1);
    }

    @Override
    public boolean insertItem(MatterPayload item, GridPosition fromPos) {
        if (item == null) return false;
        if (fromPos == null) return false;
        if (item.shape() != MatterShape.CUBE) return false;

        // stesso schema geometrico del Chromator (back a 2 celle)
        int slot = getSlotForPosition(fromPos);
        if (slot == -1) return false;

        // usiamo solo slot 0 (macchina 1-input)
        return insertIntoBuffer(0, item);
    }

    @Override
    protected GridPosition getOutputPosition() {
        int x = pos.x(); int y = pos.y(); int z = pos.z();
        return switch (orientation) {
            case NORTH -> new GridPosition(x, y, z - 1);
            case SOUTH -> new GridPosition(x + 1, y, z + 1);
            case EAST  -> new GridPosition(x + 1, y, z);
            case WEST  -> new GridPosition(x - 1, y, z + 1);
            default -> pos;
        };
    }

    private int getSlotForPosition(GridPosition senderPos) {
        int x = pos.x(); int y = pos.y(); int z = pos.z();
        GridPosition s0, s1;

        switch (orientation) {
            case NORTH -> { s0 = new GridPosition(x, y, z + 1);     s1 = new GridPosition(x + 1, y, z + 1); }
            case SOUTH -> { s0 = new GridPosition(x + 1, y, z - 1); s1 = new GridPosition(x, y, z - 1); }
            case EAST  -> { s0 = new GridPosition(x - 1, y, z);     s1 = new GridPosition(x - 1, y, z + 1); }
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
            if (currentTick >= finishTick) {
                completeProcessing();
            }
            return;
        }

        if (outputBuffer.getCount() >= MAX_OUTPUT_STACK) return;

        if (inputBuffer.getCountInSlot(0) <= 0) return;

        MatterPayload in = inputBuffer.getItemInSlot(0);
        if (in == null || in.shape() != MatterShape.CUBE) return;

        inputBuffer.decreaseSlot(0, 1);

        MatterPayload out = new MatterPayload(MatterShape.SPHERE, in.color(), in.effects());
        this.currentRecipe = new Recipe("smoothing_cube_to_sphere", List.of(in), out, 2.0f, 0);
        this.finishTick = currentTick + PROCESS_TICKS;

        saveState();
    }
}
