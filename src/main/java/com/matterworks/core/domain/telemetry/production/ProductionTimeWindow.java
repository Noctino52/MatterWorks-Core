package com.matterworks.core.domain.telemetry.production;

/**
 * Supported time windows for production telemetry.
 * Bucket size is fixed (see InMemoryProductionTelemetry).
 */
public enum ProductionTimeWindow {
    ONE_MINUTE(60),
    FIVE_MINUTES(5 * 60),
    TEN_MINUTES(10 * 60);

    private final int seconds;

    ProductionTimeWindow(int seconds) {
        this.seconds = seconds;
    }

    public int getSeconds() {
        return seconds;
    }
}
