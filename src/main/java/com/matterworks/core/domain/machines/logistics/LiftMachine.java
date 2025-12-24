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

public class LiftMachine extends PlacedMachine {

    private final MachineInventory buffer;
    private static final int TRANSPORT_TIME = 5;
    private int cooldown = 0;

    public LiftMachine(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata) {
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

        // Usa getItemInSlot(0) invece di peek()
        if (!buffer.isEmpty() && buffer.getItemInSlot(0) != null) {
            MatterPayload payload = buffer.getItemInSlot(0);

            // Output: Livello Y+1 (Alto), spostato nella direzione dell'orientamento
            GridPosition outPos = this.pos.add(0, 1, 0).add(this.orientation.toVector());
            PlacedMachine neighbor = getNeighborAt(outPos);

            if (neighbor == null) return;

            boolean moved = false;

            // Logica instanceof per gestire l'inserimento (copiata dal pattern ConveyorBelt)
            if (neighbor instanceof ConveyorBelt belt) {
                // Passiamo la posizione di Uscita Alta come sorgente
                moved = belt.insertItem(payload, currentTick);
            }
            else if (neighbor instanceof NexusMachine nexus) {
                moved = nexus.insertItem(payload, this.pos.add(0, 1, 0));
            }
            else if (neighbor instanceof ProcessorMachine proc) {
                moved = proc.insertItem(payload, this.pos.add(0, 1, 0));
            }
            else if (neighbor instanceof Splitter split) {
                moved = split.insertItem(payload, this.pos.add(0, 1, 0));
            }
            else if (neighbor instanceof Merger merger) {
                moved = merger.insertItem(payload, this.pos.add(0, 1, 0));
            }
            // Supporto concatenazione Lift->Lift o Lift->Dropper
            else if (neighbor instanceof LiftMachine lift) {
                moved = lift.insertItem(payload, this.pos.add(0, 1, 0));
            }
            else if (neighbor instanceof DropperMachine dropper) {
                moved = dropper.insertItem(payload, this.pos.add(0, 1, 0));
            }

            if (moved) {
                buffer.extractFirst();
                cooldown = TRANSPORT_TIME;
                updateMetadata();
            }
        }
    }

    // Rimossa @Override perché PlacedMachine non ha questo metodo astratto
    public boolean insertItem(MatterPayload item, GridPosition fromPos) {
        if (fromPos == null) return false;

        // Controllo 1: L'input deve essere a livello del terreno (y=0 relativo)
        if (fromPos.y() != this.pos.y()) {
            return false;
        }

        // Controllo 2: Il buffer deve avere spazio
        if (!buffer.isEmpty()) {
            return false;
        }

        // Controllo 3: ACCETTA SOLO ConveyorBelt (Specifica GDD)
        PlacedMachine sender = getNeighborAt(fromPos);
        if (!(sender instanceof ConveyorBelt)) {
            return false;
        }

        // Usa insertIntoSlot per coerenza
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