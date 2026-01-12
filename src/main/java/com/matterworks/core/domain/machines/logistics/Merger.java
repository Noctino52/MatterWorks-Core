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

public class Merger extends PlacedMachine {

    private final MachineInventory internalBuffer;
    private long outputCooldownTick = -1;
    private static final int TRANSPORT_TIME = 10;

    private int preferredInputIndex = 0;
    private int starvationTicks = 0;
    private static final int STARVATION_THRESHOLD = 5;

    public Merger(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata) {
        super(dbId, ownerId, typeId, pos, metadata);
        this.dimensions = new Vector3Int(2, 1, 1);
        this.internalBuffer = new MachineInventory(1);

        if (this.metadata.has("items")) {
            this.internalBuffer.loadState(this.metadata);
        }
        if (this.metadata.has("preferredInput")) {
            this.preferredInputIndex = this.metadata.get("preferredInput").getAsInt();
        }
        if (!internalBuffer.isEmpty()) {
            this.outputCooldownTick = 0;
        }
    }

    public boolean insertItem(MatterPayload item, GridPosition fromPos) {
        if (item == null || fromPos == null) return false;
        if (!internalBuffer.isEmpty()) return false;

        PlacedMachine sender = getNeighborAt(fromPos);
        if (!(sender instanceof ConveyorBelt)) return false;

        int inputIndex = getInputIndexFromPos(fromPos);
        if (inputIndex == -1) return false;

        if (inputIndex != preferredInputIndex) return false;

        if (internalBuffer.insertIntoSlot(0, item)) {
            togglePreference();
            this.outputCooldownTick = 0;
            this.starvationTicks = 0;
            saveState();
            return true;
        }

        return false;
    }

    @Override
    public void tick(long currentTick) {
        if (!internalBuffer.isEmpty()) {
            if (outputCooldownTick == 0) {
                outputCooldownTick = scheduleAfter(currentTick, TRANSPORT_TIME, "MERGER_MOVE");
            }
            if (currentTick >= outputCooldownTick) {
                attemptPushOutput(currentTick);
            }
        } else {
            starvationTicks++;
            if (starvationTicks >= STARVATION_THRESHOLD) {
                togglePreference();
                starvationTicks = 0;
            }
        }
    }

    private void attemptPushOutput(long currentTick) {
        GridPosition outPos = getOutputPosition();
        PlacedMachine neighbor = getNeighborAt(outPos);
        MatterPayload payload = internalBuffer.getItemInSlot(0);

        if (payload == null || neighbor == null) return;

        boolean pushed = false;

        if (neighbor instanceof ConveyorBelt belt) {
            pushed = belt.insertItem(payload, currentTick);
        } else if (neighbor instanceof NexusMachine nexus) {
            pushed = nexus.insertItem(payload, this.pos);
        } else if (neighbor instanceof ProcessorMachine proc) {
            pushed = proc.insertItem(payload, this.pos);
        } else if (neighbor instanceof Splitter split) {
            pushed = split.insertItem(payload, this.pos);
        }

        if (pushed) {
            internalBuffer.decreaseSlot(0, 1);
            outputCooldownTick = -1;
            saveState();
        }
    }

    private void togglePreference() {
        this.preferredInputIndex = (this.preferredInputIndex + 1) % 2;
    }

    private int getInputIndexFromPos(GridPosition checkPos) {
        Vector3Int f = orientationToVector();
        Vector3Int back = new Vector3Int(-f.x(), -f.y(), -f.z());

        GridPosition anchorInput = pos.add(back.x(), back.y(), back.z());
        if (checkPos.equals(anchorInput)) return 0;

        GridPosition extensionPos = getExtensionPosition();
        GridPosition extensionInput = extensionPos.add(back.x(), back.y(), back.z());
        if (checkPos.equals(extensionInput)) return 1;

        return -1;
    }

    private GridPosition getExtensionPosition() {
        return switch (orientation) {
            case NORTH -> pos.add(1, 0, 0);
            case SOUTH -> pos.add(-1, 0, 0);
            case EAST  -> pos.add(0, 0, 1);
            case WEST  -> pos.add(0, 0, -1);
            default -> pos;
        };
    }

    private GridPosition getOutputPosition() {
        Vector3Int f = orientationToVector();
        return pos.add(f.x(), f.y(), f.z());
    }

    private void saveState() {
        JsonObject invData = internalBuffer.serialize();
        this.metadata.add("items", invData.get("items"));
        this.metadata.addProperty("preferredInput", preferredInputIndex);
        this.metadata.addProperty("orientation", this.orientation.name());
        markDirty();
    }

    @Override
    public JsonObject serialize() {
        saveState();
        return super.serialize();
    }
}
