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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class MarketManager {

    // IMPORTANT:
    // sellItem() is called from Nexus.tick() at 20 TPS.
    // Therefore: NO DB calls in sellItem(). Cache refresh MUST be async.

    private static final boolean DEBUG_MARKET_LOG = false;

    private static final long FACTION_CACHE_TTL_MS = 30_000L; // 30s
    private static final long FACTION_CACHE_FAIL_BACKOFF_MS = 5_000L; // avoid retry spam on DB issues

    private static final double MIN_SELL_MULT = 0.10;
    private static final double MAX_SELL_MULT = 10.0;

    private final GridManager gridManager;
    private final MariaDBAdapter repository;
    private final ExecutorService ioExecutor;

    private final Map<MatterColor, Double> basePrices = new HashMap<>();

    private final AtomicReference<Cache> cacheRef = new AtomicReference<>(Cache.empty());
    private final AtomicBoolean refreshInFlight = new AtomicBoolean(false);
    private volatile long nextRefreshAttemptAtMs = 0L;

    public MarketManager(GridManager gridManager, MariaDBAdapter repository, ExecutorService ioExecutor) {
        this.gridManager = gridManager;
        this.repository = repository;
        this.ioExecutor = ioExecutor;

        initializePrices();

        // Kick an initial async refresh (non-blocking)
        maybeTriggerFactionCacheRefreshAsync(System.currentTimeMillis(), true);
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
    // ASYNC FACTION CACHE REFRESH (DB OUTSIDE TICK THREAD)
    // ==========================================================

    private void maybeTriggerFactionCacheRefreshAsync(long nowMs, boolean force) {
        if (nowMs < nextRefreshAttemptAtMs) return;

        Cache current = cacheRef.get();
        boolean expired = (nowMs - current.loadedAtMs) > FACTION_CACHE_TTL_MS;

        if (!force && !expired) return;

        if (!refreshInFlight.compareAndSet(false, true)) return;

        ioExecutor.submit(() -> {
            try {
                int activeFactionId = 1;
                try { activeFactionId = Math.max(1, repository.getActiveFactionId()); }
                catch (Throwable ignored) {}

                List<FactionDefinition> factions = List.of();
                try { factions = repository.loadFactions(); }
                catch (Throwable ignored) {}

                List<FactionPricingRule> rules = List.of();
                try { rules = repository.loadFactionRules(activeFactionId); }
                catch (Throwable ignored) {}

                FactionDefinition activeFaction = findFactionById(activeFactionId, factions);

                Cache updated = new Cache(
                        nowMs,
                        activeFactionId,
                        activeFaction,
                        (rules != null ? rules : List.of())
                );

                cacheRef.set(updated);
                nextRefreshAttemptAtMs = nowMs + FACTION_CACHE_TTL_MS;

            } catch (Throwable t) {
                // Backoff on failure
                nextRefreshAttemptAtMs = System.currentTimeMillis() + FACTION_CACHE_FAIL_BACKOFF_MS;
            } finally {
                refreshInFlight.set(false);
            }
        });
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
    // SELL (TICK SAFE)
    // ==========================================================

    public void sellItem(MatterPayload item, UUID sellerId) {
        if (item == null || sellerId == null) return;

        long nowMs = System.currentTimeMillis();
        maybeTriggerFactionCacheRefreshAsync(nowMs, false);

        Cache cache = cacheRef.get();

        double value = calculateBaseValue(item);

        // 0) faction multiplier (cached)
        FactionPricingRule appliedRule = null;
        double factionMult = 1.0;
        try {
            appliedRule = findBestMatchingRule(item, cache.rulesForActiveFaction);
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
                cache.activeFactionId
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

            String factionTxt = (cache.activeFaction != null)
                    ? (cache.activeFaction.displayName() + " #" + cache.activeFaction.id())
                    : ("Faction #" + Math.max(1, cache.activeFactionId));

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

    private static final class Cache {
        final long loadedAtMs;
        final int activeFactionId;
        final FactionDefinition activeFaction;
        final List<FactionPricingRule> rulesForActiveFaction;

        Cache(long loadedAtMs, int activeFactionId, FactionDefinition activeFaction, List<FactionPricingRule> rules) {
            this.loadedAtMs = loadedAtMs;
            this.activeFactionId = activeFactionId;
            this.activeFaction = activeFaction;
            this.rulesForActiveFaction = (rules != null ? rules : List.of());
        }

        static Cache empty() {
            return new Cache(0L, 1, null, List.of());
        }
    }
}
