package com.matterworks.core.infrastructure.swing;

import com.matterworks.core.common.Direction;
import com.matterworks.core.common.GridPosition;

import java.util.UUID;

final class FactoryPanelState {
    volatile UUID playerUuid;

    volatile String currentTool = "drill_mk1";
    volatile Direction currentOrientation = Direction.NORTH;
    volatile int currentLayer = 0;

    volatile GridPosition mouseHoverPos = null;

    volatile int lastHoverGX = Integer.MIN_VALUE;
    volatile int lastHoverGZ = Integer.MIN_VALUE;

    void rotate() {
        switch (currentOrientation) {
            case NORTH -> currentOrientation = Direction.EAST;
            case EAST -> currentOrientation = Direction.SOUTH;
            case SOUTH -> currentOrientation = Direction.WEST;
            case WEST -> currentOrientation = Direction.NORTH;
        }
    }

    void clearHover() {
        mouseHoverPos = null;
        lastHoverGX = Integer.MIN_VALUE;
        lastHoverGZ = Integer.MIN_VALUE;
    }
}
