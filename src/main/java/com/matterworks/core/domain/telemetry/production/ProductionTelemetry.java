package com.matterworks.core.domain.telemetry.production;

import com.matterworks.core.domain.matter.MatterPayload;

import java.util.UUID;

/**
 * In-memory telemetry collector for production/consumption/sales.
 * UI must NOT compute anything: it only asks for snapshots.
 */
public interface ProductionTelemetry {

    void recordProduced(UUID playerId, MatterPayload payload, long quantity);

    void recordConsumed(UUID playerId, MatterPayload payload, long quantity);

    /**
     * @param moneyEarned Final money delta as applied at that time (already includes multipliers).
     */
    void recordSold(UUID playerId, MatterPayload payload, long quantity, double moneyEarned);

    ProductionStatsSnapshot getSnapshot(UUID playerId, ProductionTimeWindow window);
}
