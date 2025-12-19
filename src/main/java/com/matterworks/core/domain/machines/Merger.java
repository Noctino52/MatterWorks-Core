package com.matterworks.core.domain.machines;

import com.google.gson.JsonObject;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.domain.inventory.MachineInventory;
import com.matterworks.core.domain.matter.MatterPayload;

import java.util.UUID;

public class Merger extends PlacedMachine {

    private final MachineInventory internalBuffer;
    private long outputCooldownTick = -1;
    private static final int TRANSPORT_TIME = 10;

    // Logica Round Robin & Starvation
    private int preferredInputIndex = 0; // 0 = Anchor Input (A), 1 = Extension Input (B)
    private int starvationTicks = 0;     // Conta da quanto tempo l'input preferito non fornisce items
    private static final int STARVATION_THRESHOLD = 5; // Dopo 5 tick (0.25s) senza input preferito, switcha

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

    /**
     * Gestisce l'inserimento dagli Input (Belt).
     * Accetta SOLO conveyor belt.
     * Implementa logica Round Robin con fallback.
     */
    public boolean insertItem(MatterPayload item, GridPosition fromPos) {
        if (item == null) return false;
        if (!internalBuffer.isEmpty()) return false; // Buffer pieno, la macchina è occupata

        // 1. Validazione Sorgente (Requirement: ACCETTA SOLO BELT)
        PlacedMachine sender = getNeighborAt(fromPos);
        if (!(sender instanceof ConveyorBelt)) {
            return false;
        }

        // 2. Identificazione Slot di Input (A o B)
        int inputIndex = getInputIndexFromPos(fromPos);
        if (inputIndex == -1) return false; // Posizione non valida (non è uno degli input posteriori)

        // 3. Logica Round Robin "Smart"
        // Se l'input che sta arrivando NON è quello preferito...
        if (inputIndex != preferredInputIndex) {
            // ...lo accettiamo SOLO se abbiamo raggiunto la soglia di starvation (l'altro è vuoto)
            // Oppure, per fluidità, se stiamo aspettando l'altro ma questo è pronto,
            // tecnicamente il tick loop gestisce lo switch, quindi qui rifiutiamo
            // per forzare l'alternanza corretta quando entrambi spingono.
            return false;
        }

        // 4. Inserimento
        if (internalBuffer.insertIntoSlot(0, item)) {
            // Successo! Switchiamo la preferenza all'altro input per il prossimo item
            togglePreference();
            this.outputCooldownTick = 0; // Resetta timer output
            this.starvationTicks = 0;    // Reset starvation
            saveState();
            return true;
        }

        return false;
    }

    @Override
    public void tick(long currentTick) {
        // Logica Output
        if (!internalBuffer.isEmpty()) {
            if (outputCooldownTick == 0) {
                outputCooldownTick = currentTick + TRANSPORT_TIME;
            }
            if (currentTick >= outputCooldownTick) {
                attemptPushOutput(currentTick);
            }
        } else {
            // Logica Starvation (Solo se vuoto e in attesa)
            // Se stiamo aspettando l'input preferito ma non arriva nulla, incrementiamo il contatore.
            starvationTicks++;
            if (starvationTicks >= STARVATION_THRESHOLD) {
                togglePreference(); // Cambia input preferito perché l'attuale è troppo lento/vuoto
                starvationTicks = 0;
                // Non salviamo stato qui per performance, è una variabile volatile di runtime
            }
        }
    }

    private void attemptPushOutput(long currentTick) {
        GridPosition outPos = getOutputPosition();
        PlacedMachine neighbor = getNeighborAt(outPos);
        MatterPayload payload = internalBuffer.getItemInSlot(0);

        if (payload == null || neighbor == null) return;

        boolean pushed = false;

        // Logica standard di push (simile a Conveyor/Splitter)
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

    /**
     * Determina se la posizione di provenienza è Input A (0) o Input B (1).
     * Basato sulla geometria 1x1x2 (Width=2).
     * Input A: Retro dell'Anchor.
     * Input B: Retro dell'Estensione.
     */
    private int getInputIndexFromPos(GridPosition checkPos) {
        GridPosition anchorInput = pos.add(orientation.opposite().toVector());
        if (checkPos.equals(anchorInput)) return 0;

        GridPosition extensionPos = getExtensionPosition();
        GridPosition extensionInput = extensionPos.add(orientation.opposite().toVector());
        if (checkPos.equals(extensionInput)) return 1;

        return -1;
    }

    private GridPosition getExtensionPosition() {
        int x = pos.x(); int y = pos.y(); int z = pos.z();
        return switch (orientation) {
            case NORTH -> new GridPosition(x + 1, y, z); // Estensione a destra visiva
            case SOUTH -> new GridPosition(x - 1, y, z);
            case EAST  -> new GridPosition(x, y, z + 1);
            case WEST  -> new GridPosition(x, y, z - 1);
            default -> pos;
        };
    }

    private GridPosition getOutputPosition() {
        // Output è sempre davanti all'Anchor
        return pos.add(orientation.toVector());
    }

    private void saveState() {
        JsonObject invData = internalBuffer.serialize();
        this.metadata.add("items", invData.get("items"));
        this.metadata.addProperty("preferredInput", preferredInputIndex);
        markDirty();
    }

    @Override
    public JsonObject serialize() {
        saveState();
        return super.serialize();
    }
}
