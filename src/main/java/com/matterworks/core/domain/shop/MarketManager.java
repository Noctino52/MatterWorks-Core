package com.matterworks.core.domain.shop;

import com.matterworks.core.domain.matter.MatterColor;
import com.matterworks.core.domain.matter.MatterPayload;
import com.matterworks.core.domain.matter.MatterShape;
import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.managers.GridManager;
import com.matterworks.core.ui.MariaDBAdapter;
import com.matterworks.core.ui.ServerConfig;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class MarketManager {

    private final GridManager gridManager;
    private final MariaDBAdapter repository;
    private final Map<MatterColor, Double> basePrices;

    public MarketManager(GridManager gridManager, MariaDBAdapter repository) {
        this.gridManager = gridManager;
        this.repository = repository;
        this.basePrices = new HashMap<>();
        initializePrices();
    }

    private void initializePrices() {
        basePrices.put(MatterColor.RAW, 1.0);
        basePrices.put(MatterColor.RED, 5.0);
        basePrices.put(MatterColor.BLUE, 5.0);
        basePrices.put(MatterColor.YELLOW, 5.0);
        basePrices.put(MatterColor.PURPLE, 25.0);
        basePrices.put(MatterColor.ORANGE, 25.0);
        basePrices.put(MatterColor.GREEN, 25.0);
        basePrices.put(MatterColor.WHITE, 100.0);
    }

    public void sellItem(MatterPayload item, UUID sellerId) {
        if (item == null || sellerId == null) return;

        double value = calculateBaseValue(item);
        value = applyPrestigeSellMultiplier(value, sellerId);

        gridManager.addMoney(
                sellerId,
                value,
                "MATTER_SELL",
                item.shape() != null ? item.shape().name() : "LIQUID"
        );

        String shapeTxt = (item.shape() != null ? item.shape().name() : "LIQUID");
        String colorTxt = (item.color() != null ? item.color().name() : "RAW");
        String effTxt = formatEffects(item);

        System.out.println("💰 MARKET: Venduto " + shapeTxt + " (" + colorTxt + ") " + effTxt
                + " per $" + String.format(java.util.Locale.US, "%.2f", value));
    }

    private double applyPrestigeSellMultiplier(double value, UUID sellerId) {
        PlayerProfile p = gridManager.getCachedProfile(sellerId);
        int prestige = (p != null ? Math.max(0, p.getPrestigeLevel()) : 0);
        if (prestige <= 0) return value;

        ServerConfig cfg = repository.loadServerConfig();
        double k = (cfg != null ? Math.max(0.0, cfg.prestigeSellK()) : 0.0);
        if (k <= 0.0) return value;

        double factor = 1.0 + (prestige * k);
        double out = value * factor;
        if (Double.isNaN(out) || Double.isInfinite(out)) return value;
        return Math.max(0.0, out);
    }

    private String formatEffects(MatterPayload item) {
        if (item.effects() == null || item.effects().isEmpty()) return "[NO_EFFECT]";
        String joined = item.effects().stream().map(Enum::name).collect(Collectors.joining("+"));
        return "[" + joined + "]";
    }

    private double calculateBaseValue(MatterPayload item) {
        double base = basePrices.getOrDefault(item.color(), 0.5);
        double multiplier = 1.0;
        if (item.shape() == MatterShape.SPHERE) multiplier = 1.5;
        if (item.shape() == MatterShape.PYRAMID) multiplier = 2.0;
        if (item.isComplex()) multiplier *= 1.2;
        return base * multiplier;
    }
}
