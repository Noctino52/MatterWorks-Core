package com.matterworks.core.domain.machines.processing;

import com.google.gson.JsonObject;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.domain.machines.base.PlacedMachine;
import com.matterworks.core.domain.machines.base.ProcessorMachine;
import com.matterworks.core.domain.matter.MatterEffect;
import com.matterworks.core.domain.matter.MatterPayload;
import com.matterworks.core.domain.matter.Recipe;

import java.util.List;
import java.util.UUID;

public abstract class EffectApplicatorMachine extends ProcessorMachine {

    private static final long PROCESS_TICKS = 40;

    private final MatterEffect effectToApply;

    public EffectApplicatorMachine(Long dbId,
                                   UUID ownerId,
                                   GridPosition pos,
                                   String typeId,
                                   JsonObject metadata,
                                   MatterEffect effectToApply) {
        this(dbId, ownerId, pos, typeId, metadata, effectToApply, 64);
    }

    public EffectApplicatorMachine(Long dbId,
                                   UUID ownerId,
                                   GridPosition pos,
                                   String typeId,
                                   JsonObject metadata,
                                   MatterEffect effectToApply,
                                   int maxStackPerSlot) {
        super(dbId, ownerId, pos, typeId, metadata, maxStackPerSlot);
        this.effectToApply = effectToApply;
        this.dimensions = new Vector3Int(2, 1, 1);
    }

    private boolean hasAnyEffects(MatterPayload item) {
        return item.effects() != null && !item.effects().isEmpty();
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

    private GridPosition getInputPortPosition() {
        Vector3Int f = orientationToVector();
        Vector3Int back = new Vector3Int(-f.x(), -f.y(), -f.z());
        GridPosition start = new GridPosition(pos.x() + back.x(), pos.y() + back.y(), pos.z() + back.z());
        return stepOutOfSelf(start, back);
    }

    @Override
    protected GridPosition getOutputPosition() {
        Vector3Int f = orientationToVector();
        GridPosition start = new GridPosition(pos.x() + f.x(), pos.y() + f.y(), pos.z() + f.z());
        return stepOutOfSelf(start, f);
    }

    @Override
    public boolean insertItem(MatterPayload item, GridPosition fromPos) {
        if (item == null || fromPos == null) return false;

        if (item.shape() == null) return false;
        if (hasAnyEffects(item)) return false;
        if (!fromPos.equals(getInputPortPosition())) return false;

        return insertIntoBuffer(0, item);
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

        if (in.shape() == null) return;
        if (hasAnyEffects(in)) return;

        inputBuffer.decreaseSlot(0, 1);

        MatterPayload out = new MatterPayload(in.shape(), in.color(), List.of(effectToApply));
        this.currentRecipe = new Recipe(
                "apply_" + effectToApply.name().toLowerCase(),
                List.of(in),
                out,
                2.0f,
                0
        );

        this.finishTick = scheduleAfter(currentTick, PROCESS_TICKS, "PROCESS_START");
        saveState();
    }
}
