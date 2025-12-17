package com.matterworks.core.domain.machines;

import com.matterworks.core.common.Vector3Int;

/**
 * Contiene le statistiche statiche di una macchina caricate dal DB.
 * (Dimensioni, Prezzo, etc.)
 */
public record MachineStats(
        Vector3Int dimensions,
        double basePrice,
        String nameDisplay // Opzionale: utile per la GUI
) {
    // Statistiche di fallback per blocchi ignoti
    public static MachineStats fallback(String id) {
        return new MachineStats(Vector3Int.one(), 0.0, id);
    }
}