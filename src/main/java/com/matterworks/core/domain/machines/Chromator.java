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
        inputBuffer.insertIntoSlot(0, null);
        inputBuffer.insertIntoSlot(1, null);
    }

    @Override
    public boolean insertItem(MatterPayload item) {
        boolean isDye = (item.color() != MatterColor.RAW);
        return inputBuffer.insertIntoSlot(isDye ? 1 : 0, item);
    }

    @Override
    public void tick(long currentTick) {
        super.tryEjectItem(currentTick);

        if (currentRecipe != null) {
            if (currentTick >= finishTick) completeProcessing();
            return;
        }

        int countShape = inputBuffer.getCountInSlot(0);
        int countDye = inputBuffer.getCountInSlot(1);

        if (countShape > 0 && countDye > 0) {
            MatterPayload inputShape = inputBuffer.getItemInSlot(0);
            MatterPayload inputDye = inputBuffer.getItemInSlot(1);

            inputBuffer.decreaseSlot(0, 1);
            inputBuffer.decreaseSlot(1, 1);

            MatterPayload result = new MatterPayload(inputShape.shape(), inputDye.color());

            this.currentRecipe = new com.matterworks.core.domain.matter.Recipe(
                    "chroma_op",
                    java.util.List.of(inputShape, inputDye),
                    result,
                    2.0f,
                    0
            );
            this.finishTick = currentTick + 40;
            saveState();
            System.out.println("🎨 Chromator: Mix " + inputShape.shape() + " + " + inputDye.color());
        }
    }
}