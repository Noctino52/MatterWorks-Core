package com.matterworks.core.domain.machines.logistics;

import com.google.gson.JsonObject;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.domain.inventory.MachineInventory;
import com.matterworks.core.domain.machines.production.NexusMachine;
import com.matterworks.core.domain.machines.base.PlacedMachine;
import com.matterworks.core.domain.machines.base.ProcessorMachine;
import com.matterworks.core.domain.matter.MatterPayload;
import java.util.UUID;

public class DropperMachine extends PlacedMachine {

    private final MachineInventory buffer;
    private static final int TRANSPORT_TIME = 5;
    private int cooldown = 0;

    public DropperMachine(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata) {
        super(dbId, ownerId, typeId, pos, metadata);
        this.dimensions = new Vector3Int(1, 2, 1);
        this.buffer = new MachineInventory(1);

        if (this.metadata.has("items")) {
            this.buffer.loadState(this.metadata);
        }
    }

    @Override
    public void tick(long currentTick) {
        if (cooldown > 0) {
            cooldown--;
            return;
        }

        if (!buffer.isEmpty() && buffer.getItemInSlot(0) != null) {
            MatterPayload payload = buffer.getItemInSlot(0);

            // Output: Livello Y=0 (Base), spostato nella direzione dell'orientamento
            GridPosition outPos = this.pos.add(this.orientation.toVector());
            PlacedMachine neighbor = getNeighborAt(outPos);

            if (neighbor == null) return;

            boolean moved = false;

            // Logica instanceof per i vicini
            if (neighbor instanceof ConveyorBelt belt) {
                moved = belt.insertItem(payload, currentTick);
            }
            else if (neighbor instanceof NexusMachine nexus) {
                moved = nexus.insertItem(payload, this.pos);
            }
            else if (neighbor instanceof ProcessorMachine proc) {
                moved = proc.insertItem(payload, this.pos);
            }
            else if (neighbor instanceof Splitter split) {
                moved = split.insertItem(payload, this.pos);
            }
            else if (neighbor instanceof Merger merger) {
                moved = merger.insertItem(payload, this.pos);
            }
            else if (neighbor instanceof LiftMachine lift) {
                moved = lift.insertItem(payload, this.pos);
            }
            else if (neighbor instanceof DropperMachine dropper) {
                moved = dropper.insertItem(payload, this.pos);
            }

            if (moved) {
                buffer.extractFirst();
                cooldown = TRANSPORT_TIME;
                updateMetadata();
            }
        }
    }

    // Rimossa @Override
    public boolean insertItem(MatterPayload item, GridPosition fromPos) {
        if (fromPos == null) return false;

        // Controllo 1: L'input deve essere al livello superiore (y+1)
        if (fromPos.y() != this.pos.y() + 1) {
            return false;
        }

        // Controllo 2: Buffer pieno
        if (!buffer.isEmpty()) {
            return false;
        }

        // Controllo 3: ACCETTA SOLO ConveyorBelt
        PlacedMachine sender = getNeighborAt(fromPos);
        if (!(sender instanceof ConveyorBelt)) {
            return false;
        }

        boolean success = buffer.insertIntoSlot(0, item);
        if (success) {
            updateMetadata();
        }
        return success;
    }

    private void updateMetadata() {
        this.metadata = buffer.serialize();
        this.metadata.addProperty("orientation", this.orientation.name());
        markDirty();
    }

    @Override
    public JsonObject serialize() {
        updateMetadata();
        return this.metadata;
    }
}