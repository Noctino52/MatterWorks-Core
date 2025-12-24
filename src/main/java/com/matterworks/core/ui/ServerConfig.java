package com.matterworks.core.ui;

public record ServerConfig(
        double playerStartMoney,
        int veinRaw,
        int veinRed,
        int veinBlue,
        int veinYellow,
        double sosThreshold,
        int maxInventoryMachine
) {
    // Backward-compat: vecchio codice chiama startMoney()
    public double startMoney() {
        return playerStartMoney;
    }
}
