package com.matterworks.core.ports;

import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;

/**
 * Astrazione per toccare i blocchi di Hytale.
 * Definita nel namespace Core_Ports.
 */
public interface IWorldAccess {
    void setBlock(GridPosition pos, String blockId);
    boolean isBlockSolid(GridPosition pos);
    void createVisuals(GridPosition pos, String visualId);
    Vector3Int fetchExternalBlockDimensions(String blockId);
}