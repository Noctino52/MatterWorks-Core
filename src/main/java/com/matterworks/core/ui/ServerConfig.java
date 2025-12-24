package com.matterworks.core.ui;

public record ServerConfig(
        double playerStartMoney,
        int veinRaw,
        int veinRed,
        int veinBlue,
        int veinYellow,
        double sosThreshold,
        int maxInventoryMachine,

        int plotStartingX,
        int plotStartingY,
        int plotMaxX,
        int plotMaxY,
        int plotIncreaseX,
        int plotIncreaseY
) {
    // Backward-compat: vecchio codice chiama startMoney()
    public double startMoney() {
        return playerStartMoney;
    }
}
