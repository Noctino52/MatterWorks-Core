package com.matterworks.core.common;

/**
 * Value Object immutabile che rappresenta una coordinata 3D nel mondo.
 * Usato come chiave primaria nelle HashMap del GridManager per accesso O(1).
 */
public record GridPosition(int x, int y, int z) {

    /**
     * Factory method statico per leggibilità (opzionale ma comodo).
     * Uso: GridPosition.of(10, 64, 10)
     */
    public static GridPosition of(int x, int y, int z) {
        return new GridPosition(x, y, z);
    }

    /**
     * Metodo di utilità per calcolare posizioni relative (es. "il blocco sopra").
     * Utile per logica di nastri trasportatori o controlli di adiacenza.
     */
    public GridPosition add(int dx, int dy, int dz) {
        return new GridPosition(x + dx, y + dy, z + dz);
    }

    public GridPosition add(Vector3Int vec) {
        return new GridPosition(x + vec.x(), y + vec.y(), z + vec.z());
    }

    // Nota: equals(), hashCode() e toString() sono generati automaticamente dal Record.
}