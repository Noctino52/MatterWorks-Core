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
        int plotIncreaseY,

        int prestigeVoidCoinsAdd,
        int prestigePlotBonus,
        double prestigeSellK
) {
    public double startMoney() {
        return playerStartMoney;
    }
}
