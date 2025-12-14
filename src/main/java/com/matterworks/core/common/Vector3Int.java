package com.matterworks.core.common;

/**
 * Value Object semplice per le dimensioni (Width, Height, Depth).
 * Usato da IGridComponent.
 */
public record Vector3Int(int x, int y, int z) {
    public static Vector3Int one() { return new Vector3Int(1, 1, 1); }
}