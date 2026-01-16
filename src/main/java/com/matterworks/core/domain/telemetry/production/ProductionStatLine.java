package com.matterworks.core.domain.telemetry.production;

/**
 * A single row ready for UI rendering.
 * - key: stable telemetry key (C:RED, M:CUBE:RED:BLAZING+SHINY)
 * - label: human-friendly label
 * - quantity: aggregated count in the selected window
 * - moneyEarned: only meaningful for SOLD lines; 0 for produced/consumed
 */
public final class ProductionStatLine {

    private final KeyKind kind;
    private final String key;
    private final String label;

    private final long quantity;
    private final double moneyEarned;

    public ProductionStatLine(KeyKind kind, String key, String label, long quantity, double moneyEarned) {
        this.kind = kind;
        this.key = key;
        this.label = label;
        this.quantity = quantity;
        this.moneyEarned = moneyEarned;
    }

    public KeyKind getKind() { return kind; }
    public String getKey() { return key; }
    public String getLabel() { return label; }

    public long getQuantity() { return quantity; }
    public double getMoneyEarned() { return moneyEarned; }
}
