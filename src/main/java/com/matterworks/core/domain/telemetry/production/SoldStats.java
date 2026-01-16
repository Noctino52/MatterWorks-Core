package com.matterworks.core.domain.telemetry.production;

/**
 * Aggregated sold stats for a given key within a time window.
 * moneyEarned is the final delta applied (already multiplied at the time of sale).
 */
public final class SoldStats {

    private long quantity;
    private double moneyEarned;

    public SoldStats() {
        this(0L, 0.0);
    }

    public SoldStats(long quantity, double moneyEarned) {
        this.quantity = quantity;
        this.moneyEarned = moneyEarned;
    }

    public void add(long qty, double money) {
        this.quantity += qty;
        this.moneyEarned += money;
    }

    public long getQuantity() {
        return quantity;
    }

    public double getMoneyEarned() {
        return moneyEarned;
    }
}
