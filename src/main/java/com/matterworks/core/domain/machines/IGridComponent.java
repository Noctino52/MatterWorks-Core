package com.matterworks.core.domain.machines;

import com.google.gson.JsonObject;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.ports.IWorldAccess;

public interface IGridComponent {

    void onPlace(IWorldAccess world);

    void onRemove(IWorldAccess world);

    default void onRemove() {
        onRemove(null);
    }

    JsonObject serialize();

    Vector3Int getDimensions();
}
