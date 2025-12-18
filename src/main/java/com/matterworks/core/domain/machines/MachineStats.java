package com.matterworks.core.domain.machines;

import com.matterworks.core.common.Vector3Int;

/**
 * DTO che rappresenta i dati statici di una macchina caricati dal DB.
 * Allineato con la tabella 'item_definitions' e 'machine_definitions'.
 */
public record MachineStats(
        String id,          // ID dell'item
        Vector3Int dimensions,
        double basePrice,
        int tier,           // NEW
        String modelId,     // NEW
        String category
) {
    public static MachineStats fallback(String id) {
        return new MachineStats(id, Vector3Int.one(), 0.0, 1, "model_missing", "UNKNOWN");
    }
}