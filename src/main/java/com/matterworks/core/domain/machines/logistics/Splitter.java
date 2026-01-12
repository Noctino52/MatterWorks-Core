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

public class Splitter extends PlacedMachine {

    private final MachineInventory internalBuffer;
    private long arrivalTick = -1;
    private int outputIndex = 0; // 0 = Output A, 1 = Output B
    private static final int TRANSPORT_TIME = 10;

    public Splitter(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata) {
        super(dbId, ownerId, typeId, pos, metadata);
        this.dimensions = new Vector3Int(2, 1, 1);
        this.internalBuffer = new MachineInventory(1);

        if (this.metadata.has("items")) {
            this.internalBuffer.loadState(this.metadata);
        }
        if (this.metadata.has("outputIndex")) {
            this.outputIndex = this.metadata.get("outputIndex").getAsInt();
        }
        if (!internalBuffer.isEmpty()) {
            this.arrivalTick = 0;
        }
    }

    public boolean insertItem(MatterPayload item, GridPosition fromPos) {
        if (item == null || fromPos == null) return false;
        if (!internalBuffer.isEmpty()) return false;

        Vector3Int f = orientationToVector();
        Vector3Int back = new Vector3Int(-f.x(), -f.y(), -f.z());

        GridPosition inputSpot = pos.add(back.x(), back.y(), back.z());
        if (!fromPos.equals(inputSpot)) return false;

        PlacedMachine sender = getNeighborAt(fromPos);
        if (!(sender instanceof ConveyorBelt)) return false;

        if (internalBuffer.insertIntoSlot(0, item)) {
            this.arrivalTick = 0;
            saveState();
            return true;
        }
        return false;
    }

    @Override
    public void tick(long currentTick) {
        if (internalBuffer.isEmpty()) return;

        if (arrivalTick == 0) {
            arrivalTick = scheduleAfter(currentTick, TRANSPORT_TIME, "SPLITTER_MOVE");
        }

        if (currentTick >= arrivalTick) {
            attemptSmartPush(currentTick);
        }
    }

    private void attemptSmartPush(long currentTick) {
        Vector3Int f = orientationToVector();

        GridPosition outA = pos.add(f.x(), f.y(), f.z());
        GridPosition extensionPos = getExtensionPosition();
        GridPosition outB = extensionPos.add(f.x(), f.y(), f.z());

        GridPosition[] targets = { outA, outB };
        GridPosition[] sources = { pos, extensionPos };

        int primaryIdx = outputIndex % 2;
        int secondaryIdx = (outputIndex + 1) % 2;

        boolean success = false;

        if (pushItem(targets[primaryIdx], sources[primaryIdx], currentTick)) {
            outputIndex = secondaryIdx;
            success = true;
        } else if (pushItem(targets[secondaryIdx], sources[secondaryIdx], currentTick)) {
            success = true;
        }

        if (success) {
            internalBuffer.decreaseSlot(0, 1);
            arrivalTick = -1;
            saveState();
        }
    }

    private boolean pushItem(GridPosition targetPos, GridPosition sourcePos, long currentTick) {
        PlacedMachine neighbor = getNeighborAt(targetPos);
        MatterPayload payload = internalBuffer.getItemInSlot(0);

        if (payload == null || neighbor == null) return false;

        if (neighbor instanceof ConveyorBelt belt) {
            return belt.insertItem(payload, currentTick);
        } else if (neighbor instanceof NexusMachine nexus) {
            return nexus.insertItem(payload, sourcePos);
        } else if (neighbor instanceof ProcessorMachine proc) {
            return proc.insertItem(payload, sourcePos);
        } else if (neighbor instanceof Splitter split) {
            return split.insertItem(payload, sourcePos);
        }

        return false;
    }

    private GridPosition getExtensionPosition() {
        // Extension is the second block of the 2x1x1 machine.
        // It depends on orientation and mirrors your original logic.
        return switch (orientation) {
            case NORTH -> pos.add(1, 0, 0);
            case SOUTH -> pos.add(-1, 0, 0);
            case EAST  -> pos.add(0, 0, 1);
            case WEST  -> pos.add(0, 0, -1);
            default -> pos;
        };
    }

    private void saveState() {
        JsonObject invData = internalBuffer.serialize();
        this.metadata.add("items", invData.get("items"));
        this.metadata.addProperty("outputIndex", outputIndex);
        this.metadata.addProperty("orientation", this.orientation.name());
        markDirty();
    }

    @Override
    public JsonObject serialize() {
        saveState();
        return super.serialize();
    }
}
