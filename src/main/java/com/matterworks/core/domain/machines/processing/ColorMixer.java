package com.matterworks.core.domain.machines.processing;

import com.google.gson.JsonObject;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.domain.machines.base.ProcessorMachine;
import com.matterworks.core.domain.matter.MatterColor;
import com.matterworks.core.domain.matter.MatterPayload;

import java.util.UUID;

/**
 * Mixer:
 * - Accetta SOLO "colori" (MatterPayload con shape == null) e color != RAW
 * - Mescola due colori diversi e produce un "colore" (shape == null)
 */
public class ColorMixer extends ProcessorMachine {

    public ColorMixer(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata) {
        super(dbId, ownerId, pos, typeId, metadata);
        this.dimensions = new Vector3Int(2, 1, 1);
        // garantisce i 2 slot
        if (inputBuffer.getItemInSlot(0) == null) inputBuffer.insertIntoSlot(0, null);
        if (inputBuffer.getItemInSlot(1) == null) inputBuffer.insertIntoSlot(1, null);
    }

    @Override
    public boolean insertItem(MatterPayload item, GridPosition fromPos) {
        if (fromPos == null || item == null) return false;

        // RAW non entra nel mixer
        if (item.color() == MatterColor.RAW) return false;

        int targetSlot = getSlotForPosition(fromPos);
        if (targetSlot == -1) return false;

        if (inputBuffer.getCountInSlot(targetSlot) >= MAX_INPUT_STACK) return false;

        // ✅ Normalizza: qualunque cosa arrivi (anche con shape), la trattiamo come "solo colore"
        MatterPayload dye = new MatterPayload(null, item.color());
        return insertIntoBuffer(targetSlot, dye);
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
        // 1) tenta espulsione output
        super.tryEjectItem(currentTick);

        // 2) se c'è una lavorazione in corso, controlla fine
        if (currentRecipe != null) {
            if (currentTick >= finishTick) {
                completeProcessing();
            }
            return;
        }

        // 3) se output pieno, non iniziare nuove lavorazioni
        if (outputBuffer.getCount() >= MAX_OUTPUT_STACK) return;

        // 4) servono 2 input (slot 0 e 1)
        if (inputBuffer.getCountInSlot(0) <= 0 || inputBuffer.getCountInSlot(1) <= 0) return;

        MatterPayload in0 = inputBuffer.getItemInSlot(0);
        MatterPayload in1 = inputBuffer.getItemInSlot(1);
        if (in0 == null || in1 == null) return;

        // ✅ Il mixer usa SOLO il colore: ignora totalmente la shape (così accetta anche colori secondari "incapsulati")
        MatterColor c0 = in0.color();
        MatterColor c1 = in1.color();

        // RAW non è un dye valido
        if (c0 == null || c1 == null) return;
        if (c0 == MatterColor.RAW || c1 == MatterColor.RAW) return;

        // stesso colore: non fare nulla (evita consumo inutile)
        if (c0 == c1) return;

        // 5) consuma input
        inputBuffer.decreaseSlot(0, 1);
        inputBuffer.decreaseSlot(1, 1);

        // 6) calcola mix (qui dentro deve restare la tua logica: primari+secondari -> anche WHITE)
        MatterColor mixed = MatterColor.mix(c0, c1);

        // safety: non deve uscire RAW
        if (mixed == MatterColor.RAW) mixed = MatterColor.WHITE;

        // ✅ output: SOLO COLORE (shape == null)
        MatterPayload result = new MatterPayload(null, mixed);

        // (opzionale ma consigliato) normalizza anche gli input della recipe a "solo colore"
        MatterPayload norm0 = new MatterPayload(null, c0);
        MatterPayload norm1 = new MatterPayload(null, c1);

        this.currentRecipe = new com.matterworks.core.domain.matter.Recipe(
                "mix_" + mixed.name(),
                java.util.List.of(norm0, norm1),
                result,
                1.5f,
                0
        );

        this.finishTick = currentTick + 30; // 1.5 sec (se 20 tick/s)
        saveState();

        System.out.println("Mixer: Mixing " + c0 + " + " + c1 + " -> " + mixed);
    }

}
