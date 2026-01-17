package com.matterworks.core.domain.shop;

import com.matterworks.core.domain.factions.FactionDefinition;
import com.matterworks.core.domain.factions.FactionPricingRule;
import com.matterworks.core.domain.matter.MatterColor;
import com.matterworks.core.domain.matter.MatterPayload;
import com.matterworks.core.domain.matter.MatterShape;
import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.managers.GridManager;
import com.matterworks.core.ui.MariaDBAdapter;
import com.matterworks.core.ui.ServerConfig;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class MarketManager {

    // IMPORTANT:
    // This must be tick-safe: sellItem() is called from Nexus.tick() at 20 TPS.
    // Therefore: NO DB calls per sell.

    private static final boolean DEBUG_MARKET_LOG = false;

    private static final long FACTION_CACHE_TTL_MS = 30_000L; // 30s
    private static final double MIN_SELL_MULT = 0.10;
    private static final double MAX_SELL_MULT = 10.0;

    private final GridManager gridManager;
    private final MariaDBAdapter repository;
    private final Map<MatterColor, Double> basePrices;

    // Cached faction/rules for active faction
    private volatile long factionCacheLoadedAtMs = 0L;
    private volatile int cachedActiveFactionId = -1;
    private volatile List<FactionDefinition> cachedFactions = List.of();
    private volatile List<FactionPricingRule> cachedRulesForActiveFaction = List.of();
    private volatile FactionDefinition cachedActiveFaction = null;

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

    // ==========================================================
    // CACHE REFRESH (DB ONLY ON TTL)
    // ==========================================================

    private void refreshFactionCacheIfNeeded() {
        long now = System.currentTimeMillis();

        // CRITICAL FIX:
        // Do NOT query DB each sell to check active faction.
        // We refresh only when TTL expired.
        if ((now - factionCacheLoadedAtMs) <= FACTION_CACHE_TTL_MS) return;

        int activeFactionId = 1;
        try { activeFactionId = Math.max(1, repository.getActiveFactionId()); }
        catch (Throwable ignored) {}

        List<FactionDefinition> factions = List.of();
        try { factions = repository.loadFactions(); }
        catch (Throwable ignored) {}

        List<FactionPricingRule> rules = List.of();
        try { rules = repository.loadFactionRules(activeFactionId); }
        catch (Throwable ignored) {}

        this.cachedFactions = (factions != null ? factions : List.of());
        this.cachedActiveFactionId = activeFactionId;
        this.cachedRulesForActiveFaction = (rules != null ? rules : List.of());
        this.cachedActiveFaction = findFactionById(activeFactionId, this.cachedFactions);
        this.factionCacheLoadedAtMs = now;
    }

    private FactionDefinition findFactionById(int id, List<FactionDefinition> list) {
        if (list == null) return null;
        for (FactionDefinition f : list) {
            if (f != null && f.id() == id) return f;
        }
        return null;
    }

    /**
     * Best-match rule selection:
     * - among LIKE/DISLIKE rules, pick the "most specific".
     * - tie-break: priority DESC, id ASC.
     */
    private FactionPricingRule findBestMatchingRule(MatterPayload payload, List<FactionPricingRule> rules) {
        if (payload == null || rules == null || rules.isEmpty()) return null;

        FactionPricingRule best = null;
        int bestSpec = Integer.MIN_VALUE;
        int bestPriority = Integer.MIN_VALUE;
        long bestId = Long.MAX_VALUE;

        for (FactionPricingRule r : rules) {
            if (r == null) continue;
            if (!r.matches(payload)) continue;

            int spec = r.specificityScore();
            int prio = r.priority();
            long id = r.id();

            boolean better = false;
            if (spec > bestSpec) better = true;
            else if (spec == bestSpec && prio > bestPriority) better = true;
            else if (spec == bestSpec && prio == bestPriority && id < bestId) better = true;

            if (better) {
                best = r;
                bestSpec = spec;
                bestPriority = prio;
                bestId = id;
            }
        }

        return best;
    }

    private double sanitizeMultiplier(double m) {
        if (Double.isNaN(m) || Double.isInfinite(m) || m <= 0.0) return 1.0;
        if (m < MIN_SELL_MULT) return MIN_SELL_MULT;
        if (m > MAX_SELL_MULT) return MAX_SELL_MULT;
        return m;
    }

    // ==========================================================
    // MULTIPLIERS (NO DB)
    // ==========================================================

    private double applyPrestigeSellMultiplier(double value, UUID sellerId) {
        PlayerProfile p = gridManager.getCachedProfile(sellerId);
        int prestige = (p != null ? Math.max(0, p.getPrestigeLevel()) : 0);
        if (prestige <= 0) return value;

        // IMPORTANT: do not load config from DB here. Use cached config from GridManager.
        ServerConfig cfg = gridManager.getServerConfig();
        double k = (cfg != null ? Math.max(0.0, cfg.prestigeSellK()) : 0.0);
        if (k <= 0.0) return value;

        double factor = 1.0 + (prestige * k);
        double out = value * factor;
        if (Double.isNaN(out) || Double.isInfinite(out)) return value;
        return Math.max(0.0, out);
    }

    private double applyNexusTechSellMultiplier(double value, UUID sellerId) {
        double mult = 1.0;
        try {
            mult = gridManager.getTechNexusSellMultiplier(sellerId);
        } catch (Throwable ignored) {}

        if (Double.isNaN(mult) || Double.isInfinite(mult) || mult <= 0.0) mult = 1.0;

        double out = value * mult;
        if (Double.isNaN(out) || Double.isInfinite(out)) return value;
        return Math.max(0.0, out);
    }

    private String formatEffects(MatterPayload item) {
        if (item.effects() == null || item.effects().isEmpty()) return "[NO_EFFECT]";

        // Avoid streams/Collectors in hot path
        StringBuilder sb = new StringBuilder(64);
        sb.append('[');
        for (int i = 0; i < item.effects().size(); i++) {
            if (i > 0) sb.append('+');
            sb.append(item.effects().get(i).name());
        }
        sb.append(']');
        return sb.toString();
    }

    // ==========================================================
    // SELL
    // ==========================================================

    public void sellItem(MatterPayload item, UUID sellerId) {
        if (item == null || sellerId == null) return;

        // Refresh cache only on TTL (DB happens here only every 30s)
        refreshFactionCacheIfNeeded();

        double value = calculateBaseValue(item);

        // 0) faction multiplier (cached)
        FactionPricingRule appliedRule = null;
        double factionMult = 1.0;
        try {
            appliedRule = findBestMatchingRule(item, cachedRulesForActiveFaction);
            factionMult = (appliedRule != null ? appliedRule.multiplier() : 1.0);
        } catch (Throwable ignored) {}

        factionMult = sanitizeMultiplier(factionMult);
        value = value * factionMult;

        // 1) prestige multiplier (cached config)
        value = applyPrestigeSellMultiplier(value, sellerId);

        // 2) nexus tech multiplier
        value = applyNexusTechSellMultiplier(value, sellerId);

        // Money + transaction handled by GridEconomyService async writer
        gridManager.addMoney(
                sellerId,
                value,
                "MATTER_SELL",
                (item.shape() != null ? item.shape().name() : "COLOR"),
                cachedActiveFactionId
        );

        // Telemetry (in-memory)
        try {
            if (gridManager.getProductionTelemetry() != null) {
                gridManager.getProductionTelemetry().recordSold(sellerId, item, 1L, value);
            }
        } catch (Throwable ignored) {}

        if (DEBUG_MARKET_LOG) {
            String shapeTxt = (item.shape() != null ? item.shape().name() : "COLOR");
            String colorTxt = (item.color() != null ? item.color().name() : "RAW");
            String effTxt = formatEffects(item);

            String factionTxt = (cachedActiveFaction != null)
                    ? (cachedActiveFaction.displayName() + " #" + cachedActiveFaction.id())
                    : ("Faction #" + Math.max(1, cachedActiveFactionId));

            String ruleTxt = (appliedRule != null)
                    ? ("ruleId=" + appliedRule.id()
                    + " x" + String.format(Locale.US, "%.3f", factionMult)
                    + (appliedRule.note() != null && !appliedRule.note().isBlank()
                    ? " (" + appliedRule.note() + ")"
                    : ""))
                    : "no_rule x1.000";

            System.out.println("MARKET: Sold " + shapeTxt + " (" + colorTxt + ") " + effTxt
                    + " | " + factionTxt + " | " + ruleTxt
                    + " -> $" + String.format(Locale.US, "%.2f", value));
        }
    }

    private double calculateBaseValue(MatterPayload item) {
        double base = basePrices.getOrDefault(item.color(), 0.5);

        double multiplier = 1.0;
        MatterShape shape = item.shape();

        if (shape == MatterShape.SPHERE) multiplier = 1.5;
        else if (shape == MatterShape.PYRAMID) multiplier = 2.0;

        if (item.isComplex()) multiplier *= 1.2;

        return base * multiplier;
    }
}
