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

public class Splitter extends PlacedMachine {

    private final MachineInventory internalBuffer;
    private long arrivalTick = -1;
    private int outputIndex = 0; // 0 = Uscita A, 1 = Uscita B
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

    /**
     * Inserimento Items.
     * Accetta input SOLO dal retro dell'Anchor E SOLO se proviene da un nastro.
     */
    public boolean insertItem(MatterPayload item, GridPosition fromPos) {
        if (item == null) return false;
        if (!internalBuffer.isEmpty()) return false;

        // 1. Validazione Posizione (Retro dell'Anchor)
        GridPosition inputSpot = pos.add(orientation.opposite().toVector());
        if (!fromPos.equals(inputSpot)) return false;

        // 2. Validazione Tipo Sorgente (Requirement 1: Solo Belt)
        PlacedMachine sender = getNeighborAt(fromPos);
        if (!(sender instanceof ConveyorBelt)) {
            return false; // Rifiuta input diretti da Drills, Processors, o altri Splitter
        }

        // Inserimento
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
            arrivalTick = currentTick + TRANSPORT_TIME;
        }

        if (currentTick >= arrivalTick) {
            attemptSmartPush(currentTick);
        }
    }

    /**
     * Logica Smart Round Robin (Requirement 2):
     * Tenta l'uscita preferita. Se fallisce, tenta l'alternativa.
     */
    private void attemptSmartPush(long currentTick) {
        // Calcolo Uscite
        GridPosition outA = pos.add(orientation.toVector()); // Davanti Anchor
        GridPosition extensionPos = getExtensionPosition();
        GridPosition outB = extensionPos.add(orientation.toVector()); // Davanti Estensione

        GridPosition[] targets = { outA, outB };
        GridPosition[] sources = { pos, extensionPos };

        // Indice primario (Round Robin attuale)
        int primaryIdx = outputIndex % 2;
        int secondaryIdx = (outputIndex + 1) % 2;

        boolean success = false;

        // Tenta PRIMA l'uscita designata dal Round Robin
        if (pushItem(targets[primaryIdx], sources[primaryIdx], currentTick)) {
            // Se riesce, avanza l'indice normalmente
            outputIndex = secondaryIdx;
            success = true;
        }
        // Se fallisce (bloccata/piena), tenta l'ALTRA uscita (Smart Overflow)
        else if (pushItem(targets[secondaryIdx], sources[secondaryIdx], currentTick)) {
            // Se riesce nell'alternativa, NON avanziamo l'indice
            // (Così al prossimo giro riproverà ancora quella che era bloccata, mantenendo il bilanciamento)
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
        }
        else if (neighbor instanceof NexusMachine nexus) {
            return nexus.insertItem(payload, sourcePos);
        }
        else if (neighbor instanceof ProcessorMachine proc) {
            return proc.insertItem(payload, sourcePos);
        }
        else if (neighbor instanceof Splitter split) {
            return split.insertItem(payload, sourcePos);
        }

        return false;
    }

    private GridPosition getExtensionPosition() {
        int x = pos.x(); int y = pos.y(); int z = pos.z();
        return switch (orientation) {
            case NORTH -> new GridPosition(x + 1, y, z);
            case SOUTH -> new GridPosition(x - 1, y, z);
            case EAST  -> new GridPosition(x, y, z + 1);
            case WEST  -> new GridPosition(x, y, z - 1);
            default -> pos;
        };
    }

    private void saveState() {
        JsonObject invData = internalBuffer.serialize();
        this.metadata.add("items", invData.get("items"));
        this.metadata.addProperty("outputIndex", outputIndex);
        markDirty();
    }

    @Override
    public JsonObject serialize() {
        saveState();
        return super.serialize();
    }
}