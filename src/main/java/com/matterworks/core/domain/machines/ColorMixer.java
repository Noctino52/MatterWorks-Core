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
        inputBuffer.insertIntoSlot(0, null);
        inputBuffer.insertIntoSlot(1, null);
    }

    @Override
    public boolean insertItem(MatterPayload item) {
        if (item.color() == MatterColor.RAW) return false;

        MatterPayload slot0 = inputBuffer.getItemInSlot(0);
        MatterPayload slot1 = inputBuffer.getItemInSlot(1);

        // Controllo Unicità
        if (slot0 == null || slot0.color() == item.color()) {
            if (slot1 != null && slot1.color() == item.color()) return false;
            return inputBuffer.insertIntoSlot(0, item);
        }

        if (slot1 == null || slot1.color() == item.color()) {
            if (slot0 != null && slot0.color() == item.color()) return false;
            return inputBuffer.insertIntoSlot(1, item);
        }

        return false;
    }

    @Override
    public void tick(long currentTick) {
        super.tryEjectItem(currentTick);

        if (currentRecipe != null) {
            if (currentTick >= finishTick) completeProcessing();
            return;
        }

        int count0 = inputBuffer.getCountInSlot(0);
        int count1 = inputBuffer.getCountInSlot(1);

        if (count0 > 0 && count1 > 0) {
            MatterPayload c1 = inputBuffer.getItemInSlot(0);
            MatterPayload c2 = inputBuffer.getItemInSlot(1);

            inputBuffer.decreaseSlot(0, 1);
            inputBuffer.decreaseSlot(1, 1);

            MatterColor mixed = MatterColor.mix(c1.color(), c2.color());
            MatterPayload result = new MatterPayload(MatterShape.SPHERE, mixed); // Liquido

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