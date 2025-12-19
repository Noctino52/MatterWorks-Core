package com.matterworks.core.domain.machines;

import com.google.gson.JsonObject;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.ports.IWorldAccess;
import java.util.UUID;

public class StructuralBlock extends PlacedMachine {

    public StructuralBlock(Long dbId, UUID ownerId, GridPosition pos, String typeId, JsonObject metadata) {
        super(dbId, ownerId, typeId, pos, metadata);
        this.dimensions = new Vector3Int(1, 1, 1);
    }

    /**
     * Costruttore di comodo per il piazzamento diretto.
     */
    public StructuralBlock(UUID ownerId, GridPosition pos, String nativeBlockId) {
        super(null, ownerId, "STRUCTURE_GENERIC", pos, new JsonObject());
        this.metadata.addProperty("native_id", nativeBlockId);
        this.dimensions = new Vector3Int(1, 1, 1);
        // Segniamo come sporco per assicurarci che venga salvato al prossimo autosave se necessario
        markDirty();
    }

    public String getNativeBlockId() {
        if (this.metadata.has("native_id")) {
            return this.metadata.get("native_id").getAsString();
        }
        return "hytale:stone"; // Fallback di sicurezza
    }

    @Override
    public void tick(long currentTick) {
        // Nessuna logica di tick: è un blocco strutturale inerte.
    }

    @Override
    public void onPlace(IWorldAccess world) {
        // Delega all'adattatore Hytale il piazzamento del blocco fisico
        if (world != null) {
            world.setBlock(pos, getNativeBlockId());
        }
    }

    @Override
    public void onRemove() {
        // Nessuna logica di rimozione complessa (inventari, etc.)
    }

    // La serializzazione è gestita dalla classe padre PlacedMachine che salva 'metadata'
}