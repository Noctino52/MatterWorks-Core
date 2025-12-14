package com.matterworks.core.domain.machines;

import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.database.dao.MachineDefinitionDAO;
import com.matterworks.core.ports.IWorldAccess;

import java.util.HashMap;
import java.util.Map;

public class BlockRegistry {

    private final IWorldAccess worldAdapter;
    private final Map<String, Vector3Int> dimensionCache;
    private final MachineDefinitionDAO definitionDAO;

    // Ora richiede il DAO nel costruttore
    public BlockRegistry(IWorldAccess worldAdapter, MachineDefinitionDAO definitionDAO) {
        this.worldAdapter = worldAdapter;
        this.definitionDAO = definitionDAO;
        this.dimensionCache = new HashMap<>();
    }

    /**
     * Chiamato all'avvio del server.
     */
    public void loadFromDatabase() {
        System.out.println("📏 Caricamento dimensioni macchine dal DB...");
        Map<String, Vector3Int> dbDefs = definitionDAO.loadAllDefinitions();

        dbDefs.forEach((id, dim) -> {
            dimensionCache.put(id, dim);
            System.out.println("   -> " + id + ": " + dim);
        });
    }

    public Vector3Int getDimensions(String blockId) {
        if (dimensionCache.containsKey(blockId)) {
            return dimensionCache.get(blockId);
        }
        // Fallback su Hytale se non è nel nostro DB
        Vector3Int dim = worldAdapter.fetchExternalBlockDimensions(blockId);
        dimensionCache.put(blockId, dim);
        return dim;
    }
}