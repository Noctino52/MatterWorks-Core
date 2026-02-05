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

        // Primary colors: requested baseline = $3 for a simple cube/color
        basePrices.put(MatterColor.RED, 3.0);
        basePrices.put(MatterColor.BLUE, 3.0);
        basePrices.put(MatterColor.YELLOW, 3.0);

        // Secondary / advanced colors keep higher value
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

    private FactionDefinition findFactionById(int id, List<FactionDefinition> all) {
        if (all == null || all.isEmpty()) return null;
        for (FactionDefinition f : all) {
            if (f == null) continue;
            if (f.id() == id) return f;
        }
        return null;
    }

    // ==========================================================
    // SELL
    // ==========================================================

    public double sellItem(MatterPayload item, UUID sellerId) {
        if (item == null || sellerId == null) return 0.0;

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

        // Money + transaction handled by GridEconomyService async
        gridManager.addMoney(
                sellerId,
                value,
                "MATTER_SELL",
                item.toString()
        );

        if (DEBUG_MARKET_LOG) {
            String ruleTxt = (appliedRule != null ? (" rule=" + appliedRule.id()) : " rule=(none)");
            System.out.println("[MARKET] sold=" + item + " base=" + String.format(Locale.US, "%.2f", calculateBaseValue(item))
                    + " factionMult=" + String.format(Locale.US, "%.3f", factionMult)
                    + " final=" + String.format(Locale.US, "%.2f", value)
                    + ruleTxt);
        }

        return value;
    }

    // ==========================================================
    // PRICING RULES (FACTIONS)
    // ==========================================================

    private FactionPricingRule findBestMatchingRule(MatterPayload item, List<FactionPricingRule> rules) {
        if (item == null || rules == null || rules.isEmpty()) return null;

        FactionPricingRule best = null;
        double bestMult = 1.0;

        for (FactionPricingRule r : rules) {
            if (r == null) continue;

            boolean matches = matchesRule(item, r);
            if (!matches) continue;

            double mult = sanitizeMultiplier(r.multiplier());

            // Choose rule by highest absolute multiplier distance from 1.0
            double score = Math.abs(mult - 1.0);
            double bestScore = Math.abs(bestMult - 1.0);

            if (best == null || score > bestScore) {
                best = r;
                bestMult = mult;
            }
        }

        return best;
    }

    private boolean matchesRule(MatterPayload item, FactionPricingRule r) {
        if (item == null || r == null) return false;

        // rule has optional fields: color, shape, effect
        MatterColor rc = r.color();
        MatterShape rs = r.shape();

        boolean hasEffect = (r.effect() != null);

        // Match type and combine mode
        String matchType = (r.matchType() != null ? r.matchType().name() : "CONTAINS");
        String combineMode = (r.combineMode() != null ? r.combineMode().name() : "ALL");

        boolean exact = "EXACT".equalsIgnoreCase(matchType);
        boolean any = "ANY".equalsIgnoreCase(combineMode);

        boolean mColor = (rc == null) || (item.color() == rc);
        boolean mShape = (rs == null) || (item.shape() == rs);

        boolean mEffect = true;
        if (hasEffect) {
            mEffect = item.effects() != null && item.effects().contains(r.effect());
        }

        if (exact) {
            // exact means all provided constraints must match AND the item must not have extra dimensions not in rule?
            // We keep it simple: still require those constraints; ignore "extra" effects unless effect is specified.
            boolean ok = mColor && mShape && mEffect;

            // if rule specified effect: require exactly that effect set? optional behavior:
            // keep CONTAINS semantics for effect list even in EXACT to avoid being too punishing.
            return ok;
        }

        // CONTAINS semantics
        if (any) {
            return mColor || mShape || mEffect;
        }

        // ALL (default)
        return mColor && mShape && mEffect;
    }

    private double sanitizeMultiplier(double m) {
        if (Double.isNaN(m) || Double.isInfinite(m)) return 1.0;
        if (m < MIN_SELL_MULT) return MIN_SELL_MULT;
        if (m > MAX_SELL_MULT) return MAX_SELL_MULT;
        return m;
    }

    // ==========================================================
    // BASE VALUE (COLOR + SHAPE + COMPLEXITY)
    // ==========================================================

    private double calculateBaseValue(MatterPayload item) {
        double base = basePrices.getOrDefault(item.color(), 0.5);

        double multiplier = 1.0;
        MatterShape shape = item.shape();

        if (shape == MatterShape.SPHERE) multiplier = 1.5;
        else if (shape == MatterShape.PYRAMID) multiplier = 2.0;

        if (item.isComplex()) multiplier *= 1.2;

        return base * multiplier;
    }

    // ==========================================================
    // PRESTIGE SELL MULT (DB config)
    // ==========================================================

    private double applyPrestigeSellMultiplier(double value, UUID sellerId) {
        if (sellerId == null) return value;

        PlayerProfile p = gridManager.getCachedProfile(sellerId);
        int prestige = (p != null ? Math.max(0, p.getPrestigeLevel()) : 0);
        if (prestige <= 0) return value;

        // ServerConfig is cached by repository (expected). Even if it hits DB, it's not in Nexus tick thread
        // because sellItem is called on tick thread; but here we still use repository.loadServerConfig().
        // If you ever move this to DB, consider caching the config in GridManager / runtime state.
        ServerConfig cfg = null;
        try { cfg = repository.loadServerConfig(); } catch (Throwable ignored) {}

        double k = (cfg != null ? Math.max(0.0, cfg.prestigeSellK()) : 0.0);
        if (k <= 0.0) return value;

        double mult = 1.0 + prestige * k;
        if (Double.isNaN(mult) || Double.isInfinite(mult) || mult <= 0.0) return value;

        return value * mult;
    }

    // ==========================================================
    // NEXUS TECH SELL MULT
    // ==========================================================

    private double applyNexusTechSellMultiplier(double value, UUID sellerId) {
        if (sellerId == null) return value;

        PlayerProfile p = gridManager.getCachedProfile(sellerId);
        if (p == null) return value;

        double techMult = 1.0;
        try {
            techMult = gridManager.getTechManager().getTechNexusSellMultiplier(p);
        } catch (Throwable ignored) {}

        if (Double.isNaN(techMult) || Double.isInfinite(techMult) || techMult <= 0.0) return value;

        return value * techMult;
    }

    // ==========================================================
    // CACHE STRUCT
    // ==========================================================

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
