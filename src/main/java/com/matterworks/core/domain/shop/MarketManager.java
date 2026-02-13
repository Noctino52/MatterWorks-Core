package com.matterworks.core.domain.shop;

import com.matterworks.core.domain.factions.FactionDefinition;
import com.matterworks.core.domain.factions.FactionPricingRule;
import com.matterworks.core.domain.matter.MatterColor;
import com.matterworks.core.domain.matter.MatterEffect;
import com.matterworks.core.domain.matter.MatterPayload;
import com.matterworks.core.domain.matter.MatterShape;
import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.domain.telemetry.production.ProductionTelemetry;
import com.matterworks.core.managers.GridManager;
import com.matterworks.core.ui.MariaDBAdapter;
import com.matterworks.core.ui.ServerConfig;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class MarketManager {

    // IMPORTANT:
    // sellItem() is called from Nexus.tick() at 20 TPS.
    // Therefore: NO DB calls in sellItem(). Cache refresh MUST be async.

    private static final boolean DEBUG_MARKET_LOG = false;

    private static final long CACHE_TTL_MS = 30_000L;          // 30s
    private static final long CACHE_FAIL_BACKOFF_MS = 5_000L;  // avoid retry spam on DB issues

    private static final double MIN_SELL_MULT = 0.10;
    private static final double MAX_SELL_MULT = 10.0;

    private final GridManager gridManager;
    private final MariaDBAdapter repository;
    private final ExecutorService ioExecutor;

    private final AtomicReference<Cache> cacheRef = new AtomicReference<>(Cache.empty());
    private final AtomicBoolean refreshInFlight = new AtomicBoolean(false);
    private volatile long nextRefreshAttemptAtMs = 0L;

    public MarketManager(GridManager gridManager, MariaDBAdapter repository, ExecutorService ioExecutor) {
        this.gridManager = gridManager;
        this.repository = repository;
        this.ioExecutor = ioExecutor;

        // Kick an initial async refresh (non-blocking)
        maybeTriggerCacheRefreshAsync(System.currentTimeMillis(), true);
    }

    // ==========================================================
    // ASYNC CACHE REFRESH (DB OUTSIDE TICK THREAD)
    // ==========================================================

    private void maybeTriggerCacheRefreshAsync(long nowMs, boolean force) {
        if (nowMs < nextRefreshAttemptAtMs) return;

        Cache current = cacheRef.get();
        boolean expired = (nowMs - current.loadedAtMs) > CACHE_TTL_MS;

        if (!force && !expired) return;

        if (!refreshInFlight.compareAndSet(false, true)) return;

        ioExecutor.submit(() -> {
            try {
                // ---------- Faction ----------
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

                // ---------- Matter pricing (DB-driven, composable) ----------
                Map<MatterShape, Double> shapeDb = Map.of();
                Map<MatterColor, Double> colorDb = Map.of();
                Map<MatterEffect, Double> effectDb = Map.of();

                try { shapeDb = repository.loadMatterShapeBasePrices(); } catch (Throwable ignored) {}
                try { colorDb = repository.loadMatterColorBasePrices(); } catch (Throwable ignored) {}
                try { effectDb = repository.loadMatterEffectBasePrices(); } catch (Throwable ignored) {}

                Map<MatterShape, Double> shapeMerged = mergeEnumMap(defaultShapePrices(), shapeDb, MatterShape.class);
                Map<MatterColor, Double> colorMerged = mergeEnumMap(defaultColorPrices(), colorDb, MatterColor.class);
                Map<MatterEffect, Double> effectMerged = mergeEnumMap(defaultEffectPrices(), effectDb, MatterEffect.class);

                Cache updated = new Cache(
                        nowMs,
                        activeFactionId,
                        activeFaction,
                        (rules != null ? rules : List.of()),
                        shapeMerged,
                        colorMerged,
                        effectMerged
                );

                cacheRef.set(updated);
                nextRefreshAttemptAtMs = nowMs + CACHE_TTL_MS;

            } catch (Throwable t) {
                nextRefreshAttemptAtMs = System.currentTimeMillis() + CACHE_FAIL_BACKOFF_MS;
            } finally {
                refreshInFlight.set(false);
            }
        });
    }

    private static <E extends Enum<E>> Map<E, Double> mergeEnumMap(
            Map<E, Double> base,
            Map<E, Double> override,
            Class<E> enumType
    ) {
        EnumMap<E, Double> out = new EnumMap<>(enumType);
        if (base != null) out.putAll(base);
        if (override != null) out.putAll(override);
        return out;
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
        maybeTriggerCacheRefreshAsync(nowMs, false);

        Cache cache = cacheRef.get();

        double baseValue = calculateBaseValue(item, cache);
        double value = baseValue;

        // Read config flags (cached in GridRuntimeState, DB-backed)
        ServerConfig cfg = null;
        try { cfg = gridManager.getServerConfig(); } catch (Throwable ignored) {}
        boolean enableFaction = (cfg == null) || cfg.enableFactionPriceMultiplier();
        boolean enablePrestige = (cfg == null) || cfg.enablePrestigeSellMultiplier();

        // 1) faction multiplier (optional)
        FactionPricingRule appliedRule = null;
        double factionMult = 1.0;

        if (enableFaction) {
            try {
                appliedRule = findBestMatchingRule(item, cache.rulesForActiveFaction);
                factionMult = (appliedRule != null ? appliedRule.multiplier() : 1.0);
            } catch (Throwable ignored) {}
            factionMult = sanitizeMultiplier(factionMult);
            value = value * factionMult;
        }

        // 2) prestige multiplier (optional)
        if (enablePrestige) {
            value = applyPrestigeSellMultiplier(value, sellerId);
        }

        // NO NEXUS tier boost (explicitly excluded)

        gridManager.addMoney(sellerId, value, "MATTER_SELL", item.toString());

        try {
            ProductionTelemetry telemetry = gridManager.getProductionTelemetry();
            if (telemetry != null) telemetry.recordSold(sellerId, item, 1L, value);
        } catch (Throwable ignored) {}

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

        MatterColor rc = r.color();
        MatterShape rs = r.shape();
        boolean hasEffect = (r.effect() != null);

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
            return mColor && mShape && mEffect;
        }

        if (any) {
            return mColor || mShape || mEffect;
        }

        return mColor && mShape && mEffect;
    }

    private double sanitizeMultiplier(double m) {
        if (Double.isNaN(m) || Double.isInfinite(m)) return 1.0;
        if (m < MIN_SELL_MULT) return MIN_SELL_MULT;
        if (m > MAX_SELL_MULT) return MAX_SELL_MULT;
        return m;
    }

    // ==========================================================
    // BASE VALUE (DB-DRIVEN, COMPOSABLE)
    // base = shapePrice + colorPrice + sum(effectPrices)
    // ==========================================================

    private double calculateBaseValue(MatterPayload item, Cache cache) {
        if (item == null) return 0.0;

        MatterShape shape = item.shape();
        MatterColor color = item.color();
        List<MatterEffect> effects = item.effects();

        double shapeV = 0.0;
        if (shape != null) {
            shapeV = cache.shapeBasePrices.getOrDefault(shape, 0.0);
        }

        double colorV = 0.0;
        if (color != null) {
            // default fallback if color missing in DB
            colorV = cache.colorBasePrices.getOrDefault(color, 0.5);
        }

        double effectsV = 0.0;
        if (effects != null && !effects.isEmpty()) {
            for (MatterEffect e : effects) {
                if (e == null) continue;
                effectsV += cache.effectBasePrices.getOrDefault(e, 0.0);
            }
        }

        double base = shapeV + colorV + effectsV;
        if (Double.isNaN(base) || Double.isInfinite(base) || base < 0.0) return 0.0;

        return base;
    }

    // ==========================================================
    // PRESTIGE SELL MULT (DB config)
    // ==========================================================

    private double applyPrestigeSellMultiplier(double value, UUID sellerId) {
        if (sellerId == null) return value;

        PlayerProfile p = gridManager.getCachedProfile(sellerId);
        int prestige = (p != null ? Math.max(0, p.getPrestigeLevel()) : 0);
        if (prestige <= 0) return value;

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
    // DEFAULTS (SAFE FALLBACKS)
    // ==========================================================

    private static Map<MatterShape, Double> defaultShapePrices() {
        EnumMap<MatterShape, Double> m = new EnumMap<>(MatterShape.class);
        // Keep cube at 0 so "cube primary" stays exactly 3 if color is 3
        m.put(MatterShape.CUBE, 0.0);
        m.put(MatterShape.SPHERE, 0.5);
        m.put(MatterShape.PYRAMID, 1.0);
        return m;
    }

    private static Map<MatterColor, Double> defaultColorPrices() {
        EnumMap<MatterColor, Double> m = new EnumMap<>(MatterColor.class);

        m.put(MatterColor.RAW, 1.0);

        // Primary colors: baseline requested = $3 for a simple cube/color
        m.put(MatterColor.RED, 3.0);
        m.put(MatterColor.BLUE, 3.0);
        m.put(MatterColor.YELLOW, 3.0);

        // Secondary / advanced colors
        m.put(MatterColor.PURPLE, 25.0);
        m.put(MatterColor.ORANGE, 25.0);
        m.put(MatterColor.GREEN, 25.0);
        m.put(MatterColor.WHITE, 100.0);

        return m;
    }

    private static Map<MatterEffect, Double> defaultEffectPrices() {
        EnumMap<MatterEffect, Double> m = new EnumMap<>(MatterEffect.class);
        // Placeholder defaults until DB is populated
        m.put(MatterEffect.SHINY, 15.0);
        m.put(MatterEffect.BLAZING, 20.0);
        m.put(MatterEffect.GLITCH, 30.0);
        return m;
    }

    // ==========================================================
    // CACHE STRUCT
    // ==========================================================

    private static final class Cache {
        final long loadedAtMs;

        final int activeFactionId;
        final FactionDefinition activeFaction;
        final List<FactionPricingRule> rulesForActiveFaction;

        final Map<MatterShape, Double> shapeBasePrices;
        final Map<MatterColor, Double> colorBasePrices;
        final Map<MatterEffect, Double> effectBasePrices;

        Cache(
                long loadedAtMs,
                int activeFactionId,
                FactionDefinition activeFaction,
                List<FactionPricingRule> rules,
                Map<MatterShape, Double> shapeBasePrices,
                Map<MatterColor, Double> colorBasePrices,
                Map<MatterEffect, Double> effectBasePrices
        ) {
            this.loadedAtMs = loadedAtMs;
            this.activeFactionId = activeFactionId;
            this.activeFaction = activeFaction;
            this.rulesForActiveFaction = (rules != null ? rules : List.of());
            this.shapeBasePrices = (shapeBasePrices != null ? shapeBasePrices : defaultShapePrices());
            this.colorBasePrices = (colorBasePrices != null ? colorBasePrices : defaultColorPrices());
            this.effectBasePrices = (effectBasePrices != null ? effectBasePrices : defaultEffectPrices());
        }

        static Cache empty() {
            return new Cache(
                    0L,
                    1,
                    null,
                    List.of(),
                    defaultShapePrices(),
                    defaultColorPrices(),
                    defaultEffectPrices()
            );
        }
    }
}
