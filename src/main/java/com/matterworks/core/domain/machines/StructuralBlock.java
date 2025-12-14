package com.matterworks.core.domain.machines;

import com.google.gson.JsonObject;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.ports.IWorldAccess;

/**
 * Rappresenta un blocco statico o strutturale (Muri, Decorazioni).
 * Occupa spazio nella griglia ma non ha logica di tick complessa.
 *
 */
public class StructuralBlock implements IGridComponent {

    private final String blockId;
    private final Vector3Int cachedDimensions;

    public StructuralBlock(String blockId, Vector3Int dimensions) {
        this.blockId = blockId;
        this.cachedDimensions = dimensions;
    }

    @Override
    public void onPlace(IWorldAccess world) {
        // Logica semplice: piazza il blocco visivo nel mondo
        // (La posizione viene gestita dal GridManager al momento del piazzamento)
    }

    @Override
    public void onRemove() {
        // Nessuna pulizia speciale necessaria per blocchi stupidi
    }

    @Override
    public JsonObject serialize() {
        // I blocchi strutturali potrebbero non aver bisogno di salvare metadati complessi
        // o salvano solo il loro ID se necessario.
        JsonObject json = new JsonObject();
        json.addProperty("type", "structure");
        json.addProperty("blockId", blockId);
        return json;
    }

    @Override
    public Vector3Int getDimensions() {
        return cachedDimensions;
    }

    public String getBlockId() { return blockId; }
}