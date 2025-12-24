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

/**
 * Base class per macchine che applicano UN SOLO effetto alla Matter.
 *
 * Regole:
 * - Accetta solo se item.shape() != null
 * - Accetta solo se item.effects() è vuota (nessun effetto già applicato)
 * - Reject senza consumare (stile Chromator) se non valido o da posizione errata.
 */
public abstract class EffectApplicatorMachine extends ProcessorMachine {

    private static final long PROCESS_TICKS = 40;

    private final MatterEffect effectToApply;

    public EffectApplicatorMachine(Long dbId,
                                   UUID ownerId,
                                   GridPosition pos,
                                   String typeId,
                                   JsonObject metadata,
                                   MatterEffect effectToApply) {
        super(dbId, ownerId, pos, typeId, metadata);
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
        Vector3Int f = orientation.toVector();
        Vector3Int back = new Vector3Int(-f.x(), -f.y(), -f.z());
        GridPosition start = new GridPosition(pos.x() + back.x(), pos.y() + back.y(), pos.z() + back.z());
        return stepOutOfSelf(start, back);
    }

    @Override
    protected GridPosition getOutputPosition() {
        Vector3Int f = orientation.toVector();
        GridPosition start = new GridPosition(pos.x() + f.x(), pos.y() + f.y(), pos.z() + f.z());
        return stepOutOfSelf(start, f);
    }

    @Override
    public boolean insertItem(MatterPayload item, GridPosition fromPos) {
        if (item == null || fromPos == null) return false;

        // ✅ solo se ha una shape (no liquidi)
        if (item.shape() == null) return false;

        // ✅ solo se non ha già un effetto (unico effetto applicabile)
        if (hasAnyEffects(item)) return false;

        // ✅ 1 solo input port (fuori footprint)
        if (!fromPos.equals(getInputPortPosition())) return false;

        // ✅ reject senza consumare: inseriamo solo se valido e c'è spazio
        return insertIntoBuffer(0, item);
    }

    @Override
    public void tick(long currentTick) {
        // 1) tenta espulsione
        super.tryEjectItem(currentTick);

        // 2) completa ricetta se in corso
        if (currentRecipe != null) {
            if (currentTick >= finishTick) completeProcessing();
            return;
        }

        // 3) se output pieno, non partire
        if (outputBuffer.getCount() >= MAX_OUTPUT_STACK) return;

        // 4) se input vuoto, nulla
        if (inputBuffer.getCountInSlot(0) <= 0) return;

        MatterPayload in = inputBuffer.getItemInSlot(0);
        if (in == null) return;

        // Safety: in teoria non entra mai roba non valida, ma teniamo guard
        if (in.shape() == null) return;
        if (hasAnyEffects(in)) return;

        // Consuma 1 input e avvia processo
        inputBuffer.decreaseSlot(0, 1);

        MatterPayload out = new MatterPayload(in.shape(), in.color(), List.of(effectToApply));
        this.currentRecipe = new Recipe(
                "apply_" + effectToApply.name().toLowerCase(),
                List.of(in),
                out,
                2.0f,
                0
        );
        this.finishTick = currentTick + PROCESS_TICKS;

        saveState();
    }
}
