package com.matterworks.core.model;

public record PlotUnlockState(int extraX, int extraY) {
    public static PlotUnlockState zero() {
        return new PlotUnlockState(0, 0);
    }
}
