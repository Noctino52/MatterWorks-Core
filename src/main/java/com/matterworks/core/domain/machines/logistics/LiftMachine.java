package com.matterworks.core.domain.machines.logistics;

import com.google.gson.JsonObject;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.domain.inventory.MachineInventory;
import com.matterworks.core.domain.machines.base.PlacedMachine;
import com.matterworks.core.domain.machines.base.ProcessorMachine;
import com.matterworks.core.domain.machines.production.NexusMachine;
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

        if (buffer.isEmpty()) return;

        MatterPayload payload = buffer.getItemInSlot(0);
        if (payload == null) return;

        Vector3Int f = orientationToVector();
        GridPosition outPos = new GridPosition(
                pos.x() + f.x(),
                pos.y() + 1 + f.y(),
                pos.z() + f.z()
        );

        PlacedMachine neighbor = getNeighborAt(outPos);
        if (neighbor == null) return;

        boolean moved = false;

        if (neighbor instanceof ConveyorBelt belt) {
            moved = belt.insertItem(payload, currentTick);
        } else if (neighbor instanceof NexusMachine nexus) {
            moved = nexus.insertItem(payload, new GridPosition(pos.x(), pos.y() + 1, pos.z()));
        } else if (neighbor instanceof ProcessorMachine proc) {
            moved = proc.insertItem(payload, new GridPosition(pos.x(), pos.y() + 1, pos.z()));
        } else if (neighbor instanceof Splitter split) {
            moved = split.insertItem(payload, new GridPosition(pos.x(), pos.y() + 1, pos.z()));
        } else if (neighbor instanceof Merger merger) {
            moved = merger.insertItem(payload, new GridPosition(pos.x(), pos.y() + 1, pos.z()));
        } else if (neighbor instanceof LiftMachine lift) {
            moved = lift.insertItem(payload, new GridPosition(pos.x(), pos.y() + 1, pos.z()));
        } else if (neighbor instanceof DropperMachine dropper) {
            moved = dropper.insertItem(payload, new GridPosition(pos.x(), pos.y() + 1, pos.z()));
        }

        if (moved) {
            buffer.extractFirst();

            // cooldown is a counter -> accelerated once at assignment
            cooldown = (int) computeAcceleratedTicks(TRANSPORT_TIME);

            updateMetadata();
        }
    }

    public boolean insertItem(MatterPayload item, GridPosition fromPos) {
        if (fromPos == null || item == null) return false;

        if (fromPos.y() != this.pos.y()) return false; // input at same Y level
        if (!buffer.isEmpty()) return false;

        PlacedMachine sender = getNeighborAt(fromPos);
        if (!(sender instanceof ConveyorBelt)) return false;

        boolean success = buffer.insertIntoSlot(0, item);
        if (success) updateMetadata();
        return success;
    }

    private void updateMetadata() {
        JsonObject inv = buffer.serialize();
        this.metadata.add("items", inv.get("items"));
        this.metadata.add("capacity", inv.get("capacity"));
        this.metadata.addProperty("orientation", this.orientation.name());
        markDirty();
    }

    @Override
    public JsonObject serialize() {
        updateMetadata();
        return this.metadata;
    }
}
