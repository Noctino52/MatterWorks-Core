package com.matterworks.core.domain.telemetry.production;

import java.util.Collections;
import java.util.List;

/**
 * UI-friendly view model.
 * UI must only render these lists.
 *
 * Lists are already:
 * - filtered (only present keys within window)
 * - sorted (useful default ordering)
 */
public final class ProductionStatsView {

    private final ProductionTimeWindow window;

    private final List<ProductionStatLine> producedColors;
    private final List<ProductionStatLine> producedMatters;

    private final List<ProductionStatLine> consumedColors;
    private final List<ProductionStatLine> consumedMatters;

    private final List<ProductionStatLine> soldColors;
    private final List<ProductionStatLine> soldMatters;

    private final long totalProduced;
    private final long totalConsumed;
    private final long totalSoldQuantity;
    private final double totalMoneyEarned;

    public ProductionStatsView(
            ProductionTimeWindow window,
            List<ProductionStatLine> producedColors,
            List<ProductionStatLine> producedMatters,
            List<ProductionStatLine> consumedColors,
            List<ProductionStatLine> consumedMatters,
            List<ProductionStatLine> soldColors,
            List<ProductionStatLine> soldMatters,
            long totalProduced,
            long totalConsumed,
            long totalSoldQuantity,
            double totalMoneyEarned
    ) {
        this.window = window;

        this.producedColors = Collections.unmodifiableList(producedColors);
        this.producedMatters = Collections.unmodifiableList(producedMatters);

        this.consumedColors = Collections.unmodifiableList(consumedColors);
        this.consumedMatters = Collections.unmodifiableList(consumedMatters);

        this.soldColors = Collections.unmodifiableList(soldColors);
        this.soldMatters = Collections.unmodifiableList(soldMatters);

        this.totalProduced = totalProduced;
        this.totalConsumed = totalConsumed;
        this.totalSoldQuantity = totalSoldQuantity;
        this.totalMoneyEarned = totalMoneyEarned;
    }

    public ProductionTimeWindow getWindow() { return window; }

    public List<ProductionStatLine> getProducedColors() { return producedColors; }
    public List<ProductionStatLine> getProducedMatters() { return producedMatters; }

    public List<ProductionStatLine> getConsumedColors() { return consumedColors; }
    public List<ProductionStatLine> getConsumedMatters() { return consumedMatters; }

    public List<ProductionStatLine> getSoldColors() { return soldColors; }
    public List<ProductionStatLine> getSoldMatters() { return soldMatters; }

    public long getTotalProduced() { return totalProduced; }
    public long getTotalConsumed() { return totalConsumed; }
    public long getTotalSoldQuantity() { return totalSoldQuantity; }
    public double getTotalMoneyEarned() { return totalMoneyEarned; }
}
