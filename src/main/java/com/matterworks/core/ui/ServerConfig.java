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

        // NEW: vertical build cap (Y positive only)
        int plotHeightStart,
        int plotHeightMax,
        int plotHeightIncreasePerPrestige,

        int prestigeVoidCoinsAdd,
        int prestigePlotBonus,
        double prestigeSellK,

        // prestige action fee (to actually perform prestige)
        double prestigeActionCostBase,
        double prestigeActionCostMult
) {
    // Backward-friendly alias used in some services
    public double startMoney() {
        return playerStartMoney;
    }
}
