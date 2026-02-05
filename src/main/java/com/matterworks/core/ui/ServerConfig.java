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

        int plotStartVeinRaw,
        int plotStartVeinRed,
        int plotStartVeinBlue,
        int plotStartVeinYellow,

        // NEW: cluster radius for starting veins (percentage of starting square side)
        int plotStartVeinClusterRadiusPct,

        int plotHeightStart,
        int plotHeightMax,
        int plotHeightIncreasePerPrestige,

        int prestigeVoidCoinsAdd,
        int prestigePlotBonus,
        double prestigeSellK,

        double prestigeActionCostBase,
        double prestigeActionCostMult
) {
    public double startMoney() {
        return playerStartMoney;
    }
}
