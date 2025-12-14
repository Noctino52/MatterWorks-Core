package com.matterworks.core.domain.machines;

import com.google.gson.JsonObject;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.ports.IWorldAccess;

/**
 * Interfaccia base per qualsiasi oggetto piazzabile sulla griglia.
 *
 */
public interface IGridComponent {
    void onPlace(IWorldAccess world);
    void onRemove();
    JsonObject serialize();
    Vector3Int getDimensions();
}