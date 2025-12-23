package com.matterworks.core.domain.machines;

import com.google.gson.JsonObject;
import com.matterworks.core.common.Direction;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.domain.matter.MatterPayload;
import com.matterworks.core.domain.matter.MatterShape;
import com.matterworks.core.domain.matter.Recipe;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class CuttingMachine extends ProcessorMachine {

    private static final long PROCESS_TICKS = 40;

    public CuttingMachine(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata) {
        super(dbId, ownerId, pos, typeId, metadata);
        this.dimensions = new Vector3Int(2, 1, 1);
    }

    private Vector3Int getEffectiveDims() {
        Vector3Int base = (dimensions != null) ? dimensions : Vector3Int.one();
        if (orientation == Direction.EAST || orientation == Direction.WEST) {
            return new Vector3Int(base.z(), base.y(), base.x());
        }
        return base;
    }

    private List<GridPosition> candidateOrigins() {
        Vector3Int eff = getEffectiveDims();
        List<GridPosition> origins = new ArrayList<>(eff.x() * eff.z());
        for (int ox = 0; ox < eff.x(); ox++) {
            for (int oz = 0; oz < eff.z(); oz++) {
                origins.add(new GridPosition(pos.x() - ox, pos.y(), pos.z() - oz));
            }
        }
        return origins;
    }

    private List<GridPosition> frontCells(GridPosition origin) {
        Vector3Int eff = getEffectiveDims();
        int x = origin.x(), y = origin.y(), z = origin.z();
        List<GridPosition> out = new ArrayList<>(Math.max(eff.x(), eff.z()));

        switch (orientation) {
            case NORTH -> { int frontZ = z - 1; for (int dx = 0; dx < eff.x(); dx++) out.add(new GridPosition(x + dx, y, frontZ)); }
            case SOUTH -> { int frontZ = z + eff.z(); for (int dx = 0; dx < eff.x(); dx++) out.add(new GridPosition(x + dx, y, frontZ)); }
            case EAST  -> { int frontX = x + eff.x(); for (int dz = 0; dz < eff.z(); dz++) out.add(new GridPosition(frontX, y, z + dz)); }
            case WEST  -> { int frontX = x - 1; for (int dz = 0; dz < eff.z(); dz++) out.add(new GridPosition(frontX, y, z + dz)); }
        }
        return out;
    }

    private List<GridPosition> backCells(GridPosition origin) {
        Vector3Int eff = getEffectiveDims();
        int x = origin.x(), y = origin.y(), z = origin.z();
        List<GridPosition> in = new ArrayList<>(Math.max(eff.x(), eff.z()));

        switch (orientation) {
            case NORTH -> { int backZ = z + eff.z(); for (int dx = 0; dx < eff.x(); dx++) in.add(new GridPosition(x + dx, y, backZ)); }
            case SOUTH -> { int backZ = z - 1; for (int dx = 0; dx < eff.x(); dx++) in.add(new GridPosition(x + dx, y, backZ)); }
            case EAST  -> { int backX = x - 1; for (int dz = 0; dz < eff.z(); dz++) in.add(new GridPosition(backX, y, z + dz)); }
            case WEST  -> { int backX = x + eff.x(); for (int dz = 0; dz < eff.z(); dz++) in.add(new GridPosition(backX, y, z + dz)); }
        }
        return in;
    }

    @Override
    public boolean insertItem(MatterPayload item, GridPosition fromPos) {
        if (item == null || fromPos == null) return false;
        if (item.shape() != MatterShape.SPHERE) return false;

        for (GridPosition origin : candidateOrigins()) {
            for (GridPosition p : backCells(origin)) {
                if (fromPos.equals(p)) return insertIntoBuffer(0, item);
            }
        }
        return false;
    }

    @Override
    protected GridPosition getOutputPosition() {
        return frontCells(candidateOrigins().get(0)).get(0);
    }

    private void tryEjectFrontOnlyAnchorRobust(long currentTick) {
        if (outputBuffer.isEmpty() || gridManager == null) return;

        MatterPayload item = outputBuffer.extractFirst();
        if (item == null) return;

        for (GridPosition origin : candidateOrigins()) {
            for (GridPosition target : frontCells(origin)) {
                PlacedMachine neighbor = getNeighborAt(target);
                if (neighbor == null) continue;

                if (neighbor instanceof ConveyorBelt belt) {
                    if (belt.insertItem(item, currentTick)) {
                        metadata.addProperty("lastEject", "OK");
                        saveState();
                        return;
                    }
                } else if (neighbor instanceof NexusMachine nexus) {
                    if (nexus.insertItem(item, this.pos)) {
                        metadata.addProperty("lastEject", "OK");
                        saveState();
                        return;
                    }
                }
            }
        }

        outputBuffer.insert(item);
        metadata.addProperty("lastEject", "NO_FRONT_TARGET");
        saveState();
    }

    @Override
    public void tick(long currentTick) {
        tryEjectFrontOnlyAnchorRobust(currentTick);

        if (currentRecipe != null) {
            if (currentTick >= finishTick) completeProcessing();
            return;
        }

        if (outputBuffer.getCount() >= MAX_OUTPUT_STACK) return;
        if (inputBuffer.getCountInSlot(0) <= 0) return;

        MatterPayload in = inputBuffer.getItemInSlot(0);
        if (in == null || in.shape() != MatterShape.SPHERE) return;

        inputBuffer.decreaseSlot(0, 1);

        MatterPayload out = new MatterPayload(MatterShape.PYRAMID, in.color(), in.effects());
        this.currentRecipe = new Recipe("cutting_sphere_to_pyramid", List.of(in), out, 2.0f, 0);
        this.finishTick = currentTick + PROCESS_TICKS;

        saveState();
    }
}
