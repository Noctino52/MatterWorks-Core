package com.matterworks.core.infrastructure;

public record ServerConfig(
        double startMoney,
        int veinRaw,
        int veinRed,
        int veinBlue,
        int veinYellow,
        double sosThreshold
) {}