package com.matterworks.core.domain.telemetry.production;

import java.util.Collections;
import java.util.Map;

/**
 * Immutable snapshot returned to UI (or any caller).
 * UI must NOT compute anything: it only renders these maps.
 *
 * Keys:
 *  - Color keys: C:RED
 *  - Matter keys: M:CUBE:RED:BLAZING+SHINY
 */
public final class ProductionStatsSnapshot {

    private final ProductionTimeWindow window;

    private final Map<String, Long> producedByColorKey;
    private final Map<String, Long> producedByMatterKey;

    private final Map<String, Long> consumedByColorKey;
    private final Map<String, Long> consumedByMatterKey;

    private final Map<String, SoldStats> soldByColorKey;
    private final Map<String, SoldStats> soldByMatterKey;

    private final long totalProduced;
    private final long totalConsumed;
    private final long totalSoldQuantity;
    private final double totalMoneyEarned;

    public ProductionStatsSnapshot(
            ProductionTimeWindow window,
            Map<String, Long> producedByColorKey,
            Map<String, Long> producedByMatterKey,
            Map<String, Long> consumedByColorKey,
            Map<String, Long> consumedByMatterKey,
            Map<String, SoldStats> soldByColorKey,
            Map<String, SoldStats> soldByMatterKey,
            long totalProduced,
            long totalConsumed,
            long totalSoldQuantity,
            double totalMoneyEarned
    ) {
        this.window = window;

        this.producedByColorKey = Collections.unmodifiableMap(producedByColorKey);
        this.producedByMatterKey = Collections.unmodifiableMap(producedByMatterKey);

        this.consumedByColorKey = Collections.unmodifiableMap(consumedByColorKey);
        this.consumedByMatterKey = Collections.unmodifiableMap(consumedByMatterKey);

        this.soldByColorKey = Collections.unmodifiableMap(soldByColorKey);
        this.soldByMatterKey = Collections.unmodifiableMap(soldByMatterKey);

        this.totalProduced = totalProduced;
        this.totalConsumed = totalConsumed;
        this.totalSoldQuantity = totalSoldQuantity;
        this.totalMoneyEarned = totalMoneyEarned;
    }

    public ProductionTimeWindow getWindow() { return window; }

    public Map<String, Long> getProducedByColorKey() { return producedByColorKey; }
    public Map<String, Long> getProducedByMatterKey() { return producedByMatterKey; }

    public Map<String, Long> getConsumedByColorKey() { return consumedByColorKey; }
    public Map<String, Long> getConsumedByMatterKey() { return consumedByMatterKey; }

    public Map<String, SoldStats> getSoldByColorKey() { return soldByColorKey; }
    public Map<String, SoldStats> getSoldByMatterKey() { return soldByMatterKey; }

    public long getTotalProduced() { return totalProduced; }
    public long getTotalConsumed() { return totalConsumed; }

    public long getTotalSoldQuantity() { return totalSoldQuantity; }
    public double getTotalMoneyEarned() { return totalMoneyEarned; }
}
