package com.matterworks.core.managers;

import com.matterworks.core.common.Direction;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.domain.factions.FactionRotationInfo;
import com.matterworks.core.domain.factions.FactionRotationSlot;
import com.matterworks.core.domain.machines.base.PlacedMachine;
import com.matterworks.core.domain.machines.registry.BlockRegistry;
import com.matterworks.core.domain.matter.MatterColor;
import com.matterworks.core.domain.player.BoosterStatus;
import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.domain.shop.MarketManager;
import com.matterworks.core.domain.shop.VoidShopItem;
import com.matterworks.core.model.PlotUnlockState;
import com.matterworks.core.ports.IWorldAccess;
import com.matterworks.core.ui.MariaDBAdapter;
import com.matterworks.core.domain.telemetry.production.InMemoryProductionTelemetry;
import com.matterworks.core.domain.telemetry.production.ProductionTelemetry;
import com.matterworks.core.domain.telemetry.production.ProductionStatsSnapshot;
import com.matterworks.core.domain.telemetry.production.ProductionTimeWindow;
import com.matterworks.core.domain.telemetry.production.ProductionStatsView;
import com.matterworks.core.domain.telemetry.production.ProductionStatLine;
import com.matterworks.core.domain.telemetry.production.TelemetryKeyFormatter;
import com.matterworks.core.domain.telemetry.production.KeyKind;

import com.matterworks.core.domain.factions.FactionDefinition;
import com.matterworks.core.domain.factions.FactionRotationInfo;

import com.matterworks.core.synchronization.GridSaverService;
import com.matterworks.core.ui.ServerConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;




import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GridManager {

    public static final String ITEM_INSTANT_PRESTIGE = "instant_prestige";
    public static final String ITEM_PLOT_SIZE_BREAKER = "plot_size_breaker";

    private static final String ITEM_GLOBAL_OVERCLOCK_2H = "global_overclock_2h";
    private static final String ITEM_GLOBAL_OVERCLOCK_12H = "global_overclock_12h";
    private static final String ITEM_GLOBAL_OVERCLOCK_24H = "global_overclock_24h";

    private final MariaDBAdapter repository;
    private final IWorldAccess worldAdapter;
    private final BlockRegistry blockRegistry;

    private final ExecutorService ioExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final TechManager techManager;
    private final MarketManager marketManager;

    private final GridRuntimeState state;
    private final GridWorldService world;
    private final GridEconomyService economy;

    private volatile GridSaverService saverService;


    private final AsyncEconomyWriter economyWriter;

    // ==========================================================
    // GLOBAL OVERCLOCK CACHE (server-wide, real-time)
    // ==========================================================
    private volatile long globalOverclockEndEpochMs = 0L;
    private volatile double globalOverclockMultiplier = 1.0;
    private volatile long globalOverclockLastDurationSeconds = 0L;
    private volatile long globalOverclockLastFetchMs = 0L;

    // Speed multiplier cache (shared across machines)
    private final java.util.concurrent.ConcurrentHashMap<UUID, PlayerSpeedCache> speedCache = new java.util.concurrent.ConcurrentHashMap<>();

    private static final class PlayerSpeedCache {
        final java.util.concurrent.ConcurrentHashMap<String, SpeedEntry> byType = new java.util.concurrent.ConcurrentHashMap<>();
    }

    private static final class SpeedEntry {
        final double value;
        final long validUntilMs;
        SpeedEntry(double value, long validUntilMs) {
            this.value = value;
            this.validUntilMs = validUntilMs;
        }
    }


    private final ProductionTelemetry productionTelemetry = new InMemoryProductionTelemetry();



    public GridManager(MariaDBAdapter repository, IWorldAccess worldAdapter, BlockRegistry registry) {
        this.repository = repository;
        this.worldAdapter = worldAdapter;
        this.blockRegistry = registry;

        this.techManager = new TechManager(repository, repository.getTechDefinitionDAO());

        this.state = new GridRuntimeState(repository);
        this.world = new GridWorldService(this, repository, worldAdapter, blockRegistry, techManager, ioExecutor, state);
        this.economy = new GridEconomyService(this, repository, blockRegistry, techManager, ioExecutor, state, world);

        this.marketManager = new MarketManager(this, repository, ioExecutor);
        this.economyWriter = new AsyncEconomyWriter(repository);

        refreshGlobalOverclockCache(true);
    }

    public TechManager getTechManager() { return techManager; }
    public MarketManager getMarketManager() { return marketManager; }
    public BlockRegistry getBlockRegistry() { return blockRegistry; }

    public List<VoidShopItem> getVoidShopCatalog() { return economy.getVoidShopCatalog(); }
    public boolean buyVoidShopItem(UUID playerId, String premiumItemId, int amount) { return economy.buyVoidShopItem(playerId, premiumItemId, amount); }
    public boolean canInstantPrestige(UUID ownerId) { return economy.canInstantPrestige(ownerId); }
    public void instantPrestigeUser(UUID ownerId) { economy.instantPrestigeUser(ownerId); }

    public Map<GridPosition, PlacedMachine> getSnapshot(UUID ownerId) { return world.getSnapshot(ownerId); }
    public Map<GridPosition, PlacedMachine> getAllMachinesSnapshot() { return world.getAllMachinesSnapshot(); }
    public Map<GridPosition, MatterColor> getTerrainResources(UUID playerId) { return world.getTerrainResources(playerId); }

    public record PlotAreaInfo(
            int startingX, int startingY,
            int maxX, int maxY,
            int increaseX, int increaseY,
            int extraX, int extraY
    ) {
        public int unlockedX() { return Math.min(maxX, Math.max(1, startingX + Math.max(0, extraX))); }
        public int unlockedY() { return Math.min(maxY, Math.max(1, startingY + Math.max(0, extraY))); }

        public int minX() { return Math.max(0, (maxX - unlockedX()) / 2); }
        public int minZ() { return Math.max(0, (maxY - unlockedY()) / 2); }

        public int maxXExclusive() { return Math.min(maxX, minX() + unlockedX()); }
        public int maxZExclusive() { return Math.min(maxY, minZ() + unlockedY()); }
    }

    public PlotAreaInfo getPlotAreaInfo(UUID ownerId) { return world.getPlotAreaInfo(ownerId); }

    public boolean increasePlotUnlockedArea(UUID ownerId) {
        if (ownerId == null) return false;

        state.touchPlayer(ownerId);

        PlayerProfile p = state.getCachedProfile(ownerId);
        if (p == null) return false;

        final boolean isAdmin = p.isAdmin();

        // Player rule: allow only if they own at least 1 breaker item.
        if (!isAdmin) {
            int owned = 0;
            try {
                owned = repository.getInventoryItemCount(ownerId, ITEM_PLOT_SIZE_BREAKER);
            } catch (Throwable ignored) {}

            if (owned <= 0) {
                System.out.println("⚠️ PLOT SIZE INCREASE denied (missing " + ITEM_PLOT_SIZE_BREAKER + "): " + ownerId);
                return false;
            }
        }

        // Apply expansion
        final boolean ok = isAdmin
                ? world.increasePlotUnlockedArea(ownerId)           // admin-only path
                : world.increasePlotUnlockedAreaUnchecked(ownerId); // player path

        if (!ok) return false;

        // Consume item only for non-admins and only if expansion succeeded.
        if (!isAdmin) {
            try {
                repository.modifyInventoryItem(ownerId, ITEM_PLOT_SIZE_BREAKER, -1);

                PlayerProfile fresh = null;
                try { fresh = repository.loadPlayerProfile(ownerId); } catch (Throwable ignored) {}
                if (fresh == null) fresh = p;

                repository.logTransaction(fresh, "PLOT_SIZE_BREAKER_USED", "ITEM", 1, ITEM_PLOT_SIZE_BREAKER);

                // If you track global stats:
                // repository.addVoidPlotItemBreakerIncreased(1);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }

        return true;
    }


    public boolean unlockTechNode(UUID playerId, String nodeId) {
        if (playerId == null || nodeId == null || nodeId.isBlank()) return false;
        return economy.unlockTechNode(playerId, nodeId);
    }

    public int getUnlockedMachineTier(UUID ownerId, String machineTypeId) {
        if (ownerId == null || machineTypeId == null || machineTypeId.isBlank()) return 1;

        var p = state.getCachedProfile(ownerId);
        if (p == null) return 1;

        try {
            return techManager.getUnlockedTierForMachine(p, machineTypeId);
        } catch (Throwable ignored) {
            return 1;
        }
    }

    public void setSaverService(GridSaverService saverService) {
        this.saverService = saverService;
    }





    boolean increasePlotUnlockedAreaUnchecked(UUID ownerId) {
        state.touchPlayer(ownerId);
        return increasePlotUnlockedAreaInternal(ownerId);
    }

    private boolean increasePlotUnlockedAreaInternal(UUID ownerId) {
        GridManager.PlotAreaInfo info = getPlotAreaInfo(ownerId);
        PlotUnlockState cur = state.plotUnlockCache.getOrDefault(ownerId, PlotUnlockState.zero());

        int newExtraX = cur.extraX() + info.increaseX();
        int newExtraY = cur.extraY() + info.increaseY();

        int unlockedX = Math.min(info.maxX(), info.startingX() + newExtraX);
        int unlockedY = Math.min(info.maxY(), info.startingY() + newExtraY);

        PlotUnlockState next = new PlotUnlockState(
                Math.max(0, unlockedX - info.startingX()),
                Math.max(0, unlockedY - info.startingY())
        );

        if (!repository.updatePlotUnlockState(ownerId, next)) return false;
        state.plotUnlockCache.put(ownerId, next);
        return true;
    }

    public boolean decreasePlotUnlockedArea(UUID ownerId) { return world.decreasePlotUnlockedArea(ownerId); }

    public void reloadMinutesToInactive() { state.reloadMinutesToInactive(); }
    public void touchPlayer(UUID ownerId) { state.touchPlayer(ownerId); }

    public PlayerProfile getCachedProfile(UUID uuid) { return state.getCachedProfile(uuid); }
    public boolean buyItem(UUID playerId, String itemId, int amount) { return economy.buyItem(playerId, itemId, amount); }
    public double getEffectiveShopUnitPrice(UUID playerId, String itemId) { return economy.getEffectiveShopUnitPrice(playerId, itemId); }
    public double getEffectiveShopUnitPrice(PlayerProfile p, String itemId) { return economy.getEffectiveShopUnitPrice(p, itemId); }
    public boolean attemptBailout(UUID ownerId) { return economy.attemptBailout(ownerId); }

    public void resetUserPlot(UUID ownerId) { economy.resetUserPlot(ownerId); }
    public void prestigeUser(UUID ownerId) { economy.prestigeUser(ownerId); }
    public PlayerProfile createNewPlayer(String username) { return economy.createNewPlayer(username); }
    public void deletePlayer(UUID uuid) { economy.deletePlayer(uuid); }

    public void preloadPlotFromDB(UUID ownerId) { world.preloadPlotFromDB(ownerId); }
    public void loadPlotFromDB(UUID ownerId) { world.loadPlotFromDB(ownerId); }

    public void tick(long t) {

        // Safety refresh for global overclock cache:
        // - NEVER do DB work in getEffectiveMachineSpeedMultiplier (hot path).
        // - Do a very rare refresh here to heal cache after restarts / external DB edits.
        // 5 minutes @ 20 TPS = 6000 ticks
        if (t % 6000 == 0) {
            try {
                refreshGlobalOverclockCache(false);
            } catch (Throwable ignored) {}
        }

        // Rotation check throttled to reduce DB spikes.
        // 20 TPS -> 2400 ticks ~= 2 minutes.
        if (t % 2400 == 0) {
            try {
                int hours = repository.getFactionRotationHours();
                if (hours > 0) {

                    var factions = repository.loadFactions();
                    if (factions != null && factions.size() > 1) {

                        factions = new ArrayList<>(factions);
                        factions.sort(Comparator
                                .comparingInt((com.matterworks.core.domain.factions.FactionDefinition f) -> f.sortOrder())
                                .thenComparingInt(com.matterworks.core.domain.factions.FactionDefinition::id));

                        long periodMs = (long) hours * 3600_000L;
                        if (periodMs <= 0L) periodMs = 3600_000L;

                        long nowMs = System.currentTimeMillis();
                        long slot = Math.floorDiv(nowMs, periodMs);

                        int idx = (int) Math.floorMod(slot, factions.size());
                        int desiredFactionId = factions.get(idx).id();

                        int currentFactionId = repository.getActiveFactionId();
                        if (currentFactionId != desiredFactionId) {
                            repository.setActiveFactionId(desiredFactionId);

                            var def = factions.get(idx);
                            System.out.println("[FACTIONS] Rotation applied -> "
                                    + def.displayName() + " (#" + def.id() + ")"
                                    + " | hours=" + hours
                                    + " | nowMs=" + nowMs);
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        world.tick(t);
    }

    // Returns a direct view of the player's grid map (ConcurrentHashMap).
// Used by the async saver to avoid allocating a full copy (HashMap) every autosave.
// Safe to iterate because the underlying map is concurrent.
    public Map<GridPosition, PlacedMachine> getUnsafeGridView(UUID ownerId) {
        if (ownerId == null) return Collections.emptyMap();
        Map<GridPosition, PlacedMachine> g = state.playerGrids.get(ownerId);
        return g != null ? g : Collections.emptyMap();
    }





    public boolean placeStructure(UUID ownerId, GridPosition pos, String nativeBlockId) { return world.placeStructure(ownerId, pos, nativeBlockId); }
    public boolean placeMachine(UUID ownerId, GridPosition pos, String typeId, Direction orientation) { return world.placeMachine(ownerId, pos, typeId, orientation); }
    public void removeComponent(UUID ownerId, GridPosition pos) { world.removeComponent(ownerId, pos); }

    public void saveAndUnloadSpecific(UUID ownerId) { world.saveAndUnloadSpecific(ownerId); }

    public PlacedMachine getMachineAt(UUID ownerId, GridPosition pos) { return world.getMachineAt(ownerId, pos); }

    public int getEffectiveItemPlacedOnPlotCap(UUID ownerId) { return state.getEffectiveItemPlacedOnPlotCap(ownerId); }

    // ==========================================================
    // PERSISTENT "+" MECHANIC
    // plots.void_itemcap_extra += server_gamestate.itemcap_void_increase_step
    // ==========================================================
    public boolean increaseMaxItemPlacedOnPlotCap(UUID requesterId) {
        if (requesterId == null) return false;

        state.touchPlayer(requesterId);

        PlayerProfile p = state.getCachedProfile(requesterId);
        if (p == null) return false;

        final String BREAKER_ITEM_ID = "block_cap_breaker";
        final boolean isAdmin = p.isAdmin();

        // Player rule: allow only if they own at least 1 breaker item.
        if (!isAdmin) {
            int owned = 0;
            try {
                owned = repository.getInventoryItemCount(requesterId, BREAKER_ITEM_ID);
            } catch (Throwable ignored) {}

            if (owned <= 0) {
                System.out.println("⚠️ VOID ITEM CAP INCREASE denied (missing " + BREAKER_ITEM_ID + "): " + requesterId);
                return false;
            }
        }

        int step;
        try {
            step = repository.getVoidItemCapIncreaseStep();
        } catch (Throwable t) {
            return false;
        }

        step = Math.max(0, step);
        if (step <= 0) return false;

        int before = state.getEffectiveItemPlacedOnPlotCap(requesterId);

        int newStored = repository.addPlotVoidItemCapExtra(requesterId, step);
        state.setPersistedVoidItemCapExtra(requesterId, newStored);

        int after = state.getEffectiveItemPlacedOnPlotCap(requesterId);
        boolean increased = after > before;

        // Consume item only for non-admins and only if we actually increased.
        if (increased && !isAdmin) {
            try {
                repository.modifyInventoryItem(requesterId, BREAKER_ITEM_ID, -1);

                PlayerProfile fresh = null;
                try { fresh = repository.loadPlayerProfile(requesterId); } catch (Throwable ignored) {}
                if (fresh == null) fresh = p;

                repository.logTransaction(fresh, "ITEMCAP_BREAKER_USED", "ITEM", 1, BREAKER_ITEM_ID);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }

        return increased;
    }

    // ==========================================================
    // OVERCLOCK USE (player + global)
    // ==========================================================
    public boolean canUseOverclock(UUID playerId, String itemId) {
        return economy.canUseOverclock(playerId, itemId);
    }

    public boolean useOverclock(UUID playerId, String itemId) {
        return economy.useOverclock(playerId, itemId);
    }

    // ==========================================================
    // BOOSTERS LIST
    // ==========================================================
    public List<BoosterStatus> getActiveBoosters(UUID ownerId) {
        List<BoosterStatus> out = new ArrayList<>();
        if (ownerId == null) return out;

        var p = state.getCachedProfile(ownerId);
        if (p == null) return out;

        // Player overclock (playtime-based)
        long playtime = state.getPlaytimeSecondsCached(ownerId);
        long remaining = p.getOverclockRemainingSeconds(playtime);
        if (remaining != 0L && p.getOverclockMultiplier() > 1.0) {
            String id = inferOverclockBoosterId(p.getOverclockDurationSeconds());
            String name = inferOverclockDisplayName(p.getOverclockDurationSeconds());

            out.add(new BoosterStatus(
                    id,
                    name,
                    p.getOverclockMultiplier(),
                    remaining,
                    p.getOverclockDurationSeconds()
            ));
        }

        // Global overclock (real-time)
        refreshGlobalOverclockCache(false);
        long globalRemaining = getGlobalOverclockRemainingSeconds();
        double globalMult = getGlobalOverclockMultiplierNow();

        if (globalRemaining > 0L && globalMult > 1.0) {
            String gid = inferGlobalOverclockBoosterId(globalOverclockLastDurationSeconds);
            String gname = inferGlobalOverclockDisplayName(globalOverclockLastDurationSeconds);

            out.add(new BoosterStatus(
                    gid,
                    gname,
                    globalMult,
                    globalRemaining,
                    globalOverclockLastDurationSeconds
            ));
        }

        return out;
    }

    private String inferOverclockBoosterId(long durationSeconds) {
        if (durationSeconds == -1) return "overclock_life";
        if (durationSeconds == 2L * 3600L) return "overclock_2h";
        if (durationSeconds == 12L * 3600L) return "overclock_12h";
        if (durationSeconds == 24L * 3600L) return "overclock_24h";
        return "overclock_custom";
    }

    private String inferOverclockDisplayName(long durationSeconds) {
        if (durationSeconds == -1) return "Overclock (Lifetime)";
        if (durationSeconds == 2L * 3600L) return "Overclock (2h)";
        if (durationSeconds == 12L * 3600L) return "Overclock (12h)";
        if (durationSeconds == 24L * 3600L) return "Overclock (24h)";
        return "Overclock";
    }

    private String inferGlobalOverclockBoosterId(long durationSeconds) {
        if (durationSeconds == 2L * 3600L) return ITEM_GLOBAL_OVERCLOCK_2H;
        if (durationSeconds == 12L * 3600L) return ITEM_GLOBAL_OVERCLOCK_12H;
        if (durationSeconds == 24L * 3600L) return ITEM_GLOBAL_OVERCLOCK_24H;
        return "global_overclock";
    }

    private String inferGlobalOverclockDisplayName(long durationSeconds) {
        if (durationSeconds == 2L * 3600L) return "Global Overclock (2h)";
        if (durationSeconds == 12L * 3600L) return "Global Overclock (12h)";
        if (durationSeconds == 24L * 3600L) return "Global Overclock (24h)";
        return "Global Overclock";
    }

// ==========================================================
// EFFECTIVE MACHINE SPEED (machine base speed * player overclock * global overclock * tech tier)
// ==========================================================
public double getEffectiveMachineSpeedMultiplier(UUID ownerId, String machineTypeId) {
    double machineSpeed = 1.0;
    try {
        machineSpeed = blockRegistry.getSpeed(machineTypeId);
    } catch (Throwable ignored) {}

    if (Double.isNaN(machineSpeed) || Double.isInfinite(machineSpeed) || machineSpeed <= 0.0) {
        machineSpeed = 1.0;
    }

    if (ownerId == null || machineTypeId == null) return machineSpeed;

    // Shared cache across all machines of same player+type
    long nowMs = System.currentTimeMillis();
    PlayerSpeedCache psc = speedCache.computeIfAbsent(ownerId, _id -> new PlayerSpeedCache());
    SpeedEntry cached = psc.byType.get(machineTypeId);
    if (cached != null && nowMs < cached.validUntilMs) {
        return cached.value;
    }

    var p = state.getCachedProfile(ownerId);
    if (p == null) return machineSpeed;

    long playtime = state.getPlaytimeSecondsCached(ownerId);
    double ocPlayer = p.getActiveOverclockMultiplier(playtime);

    double ocGlobal = getGlobalOverclockMultiplierNow();

    double techMult = 1.0;
    try {
        techMult = techManager.getTechSpeedMultiplierForMachine(p, machineTypeId);
    } catch (Throwable ignored) {}
    if (Double.isNaN(techMult) || Double.isInfinite(techMult) || techMult <= 0.0) techMult = 1.0;

    double out = machineSpeed * ocPlayer * ocGlobal * techMult;
    if (Double.isNaN(out) || Double.isInfinite(out) || out <= 0.0) out = 1.0;

    // 1s TTL + small deterministic jitter to avoid stampede
    long jitter = (machineTypeId.hashCode() & 0xFFL); // 0..255ms
    long validUntil = nowMs + 1000L + jitter;

    psc.byType.put(machineTypeId, new SpeedEntry(out, validUntil));
    return out;
}




    // ==========================================================
// NEXUS SELL MULTIPLIER (Tech Tier 2/3)
// ==========================================================
    public double getTechNexusSellMultiplier(UUID ownerId) {
        if (ownerId == null) return 1.0;

        var p = state.getCachedProfile(ownerId);
        if (p == null) return 1.0;

        double mult = 1.0;
        try {
            mult = techManager.getTechNexusSellMultiplier(p);
        } catch (Throwable ignored) {}

        if (Double.isNaN(mult) || Double.isInfinite(mult) || mult <= 0.0) return 1.0;
        return mult;
    }



    // ==========================================================
    // GLOBAL OVERCLOCK CACHE API (used by GridEconomyService)
    // ==========================================================
    void _setGlobalOverclockStateCached(long endEpochMs, double multiplier, long lastDurationSeconds) {
        long endMs = Math.max(0L, endEpochMs);
        double mult = multiplier;
        if (Double.isNaN(mult) || Double.isInfinite(mult) || mult <= 0.0) mult = 1.0;

        this.globalOverclockEndEpochMs = endMs;
        this.globalOverclockMultiplier = mult;
        this.globalOverclockLastDurationSeconds = Math.max(0L, lastDurationSeconds);
        this.globalOverclockLastFetchMs = System.currentTimeMillis();
    }

    private void refreshGlobalOverclockCache(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && (now - globalOverclockLastFetchMs) < 2000L) return;

        try {
            long endMs = Math.max(0L, repository.getGlobalOverclockEndEpochMs());
            double mult = repository.getGlobalOverclockMultiplier();
            long lastDur = Math.max(0L, repository.getGlobalOverclockLastDurationSeconds());

            if (Double.isNaN(mult) || Double.isInfinite(mult) || mult <= 0.0) mult = 1.0;

            globalOverclockEndEpochMs = endMs;
            globalOverclockMultiplier = mult;
            globalOverclockLastDurationSeconds = lastDur;
            globalOverclockLastFetchMs = now;
        } catch (Throwable ignored) {
            // keep previous cached values
        }
    }

    private double getGlobalOverclockMultiplierNow() {
        long endMs = globalOverclockEndEpochMs;
        if (endMs <= 0L) return 1.0;

        long now = System.currentTimeMillis();
        if (now >= endMs) return 1.0;

        double mult = globalOverclockMultiplier;
        if (Double.isNaN(mult) || Double.isInfinite(mult) || mult <= 0.0) return 1.0;

        return mult;
    }

    private long getGlobalOverclockRemainingSeconds() {
        long endMs = globalOverclockEndEpochMs;
        if (endMs <= 0L) return 0L;

        long now = System.currentTimeMillis();
        long diffMs = endMs - now;
        if (diffMs <= 0L) return 0L;

        return (long) Math.ceil(diffMs / 1000.0);
    }

    public ProductionTelemetry getProductionTelemetry() { return productionTelemetry; }

    public ProductionStatsSnapshot getProductionStats(UUID playerId, ProductionTimeWindow window) {
        if (playerId == null) {
            return productionTelemetry.getSnapshot(UUID.randomUUID(), ProductionTimeWindow.ONE_MINUTE); // empty-ish safe fallback
        }
        if (window == null) window = ProductionTimeWindow.ONE_MINUTE;
        return productionTelemetry.getSnapshot(playerId, window);
    }

    public ProductionStatsView getProductionStatsView(UUID playerId, ProductionTimeWindow window) {
        if (window == null) window = ProductionTimeWindow.ONE_MINUTE;

        ProductionStatsSnapshot snap = getProductionStats(playerId, window);

        var producedColors = snap.getProducedByColorKey().entrySet().stream()
                .map(e -> new ProductionStatLine(
                        KeyKind.COLOR,
                        e.getKey(),
                        TelemetryKeyFormatter.toLabel(e.getKey()),
                        e.getValue(),
                        0.0
                ))
                .sorted((a, b) -> Long.compare(b.getQuantity(), a.getQuantity()))
                .toList();

        var producedMatters = snap.getProducedByMatterKey().entrySet().stream()
                .map(e -> new ProductionStatLine(
                        KeyKind.MATTER,
                        e.getKey(),
                        TelemetryKeyFormatter.toLabel(e.getKey()),
                        e.getValue(),
                        0.0
                ))
                .sorted((a, b) -> Long.compare(b.getQuantity(), a.getQuantity()))
                .toList();

        var consumedColors = snap.getConsumedByColorKey().entrySet().stream()
                .map(e -> new ProductionStatLine(
                        KeyKind.COLOR,
                        e.getKey(),
                        TelemetryKeyFormatter.toLabel(e.getKey()),
                        e.getValue(),
                        0.0
                ))
                .sorted((a, b) -> Long.compare(b.getQuantity(), a.getQuantity()))
                .toList();

        var consumedMatters = snap.getConsumedByMatterKey().entrySet().stream()
                .map(e -> new ProductionStatLine(
                        KeyKind.MATTER,
                        e.getKey(),
                        TelemetryKeyFormatter.toLabel(e.getKey()),
                        e.getValue(),
                        0.0
                ))
                .sorted((a, b) -> Long.compare(b.getQuantity(), a.getQuantity()))
                .toList();

        var soldColors = snap.getSoldByColorKey().entrySet().stream()
                .map(e -> new ProductionStatLine(
                        KeyKind.COLOR,
                        e.getKey(),
                        TelemetryKeyFormatter.toLabel(e.getKey()),
                        e.getValue().getQuantity(),
                        e.getValue().getMoneyEarned()
                ))
                .sorted((a, b) -> Double.compare(b.getMoneyEarned(), a.getMoneyEarned()))
                .toList();

        // 1) Prefer "real matters" (shape != null) i.e. not M:LIQUID:<COLOR>:NO_EFFECT
        var soldMattersReal = snap.getSoldByMatterKey().entrySet().stream()
                .filter(e -> !TelemetryKeyFormatter.isColorOnlyMatterKey(e.getKey()))
                .map(e -> new ProductionStatLine(
                        KeyKind.MATTER,
                        e.getKey(),
                        TelemetryKeyFormatter.toLabel(e.getKey()),
                        e.getValue().getQuantity(),
                        e.getValue().getMoneyEarned()
                ))
                .sorted((a, b) -> Double.compare(b.getMoneyEarned(), a.getMoneyEarned()))
                .toList();

        // 2) Fallback: if everything is being recorded as color-only, show soldColors instead
        var soldForUi = !soldMattersReal.isEmpty()
                ? soldMattersReal
                : soldColors;

        return new ProductionStatsView(
                window,
                producedColors,
                producedMatters,
                consumedColors,
                consumedMatters,
                soldColors,
                soldForUi,
                snap.getTotalProduced(),
                snap.getTotalConsumed(),
                snap.getTotalSoldQuantity(),
                snap.getTotalMoneyEarned()
        );
    }



    public boolean canPerformPrestige(UUID ownerId) {
        return economy.canPerformPrestige(ownerId);
    }

    public double getPrestigeActionCost(UUID ownerId) {
        var p = state.getCachedProfile(ownerId);
        if (p == null) return 0.0;
        return economy.getPrestigeActionCost(p);
    }

    public FactionRotationInfo getFactionRotationInfo() {
        try {
            int hours = repository.getFactionRotationHours();

            // Current active faction as stored/resolved from DB (legacy-safe)
            int activeId = Math.max(1, repository.getActiveFactionId());

            List<FactionDefinition> factions = repository.loadFactions();
            if (factions == null || factions.isEmpty()) {
                return FactionRotationInfo.disabled(activeId, "Faction #" + activeId);
            }

            // Deterministic ordering (same as rotation logic)
            List<FactionDefinition> ordered = new ArrayList<>(factions);
            ordered.sort(Comparator
                    .comparingInt(FactionDefinition::sortOrder)
                    .thenComparingInt(FactionDefinition::id));

            FactionDefinition currentDef = ordered.stream()
                    .filter(f -> f != null && f.id() == activeId)
                    .findFirst()
                    .orElse(null);

            String currentName = (currentDef != null) ? currentDef.displayName() : ("Faction #" + activeId);

            if (hours <= 0) {
                return FactionRotationInfo.disabled(activeId, currentName);
            }

            long periodMs = (long) hours * 3600_000L;
            if (periodMs <= 0L) periodMs = 3600_000L;

            long nowMs = System.currentTimeMillis();

            long slot = Math.floorDiv(nowMs, periodMs);
            int idx = (int) Math.floorMod(slot, ordered.size());

            int computedNowId = ordered.get(idx).id();
            String computedNowName = ordered.get(idx).displayName();

            long nextChange = (slot + 1L) * periodMs;
            long remaining = Math.max(0L, nextChange - nowMs);

            int nextIdx = (idx + 1) % ordered.size();
            int nextId = ordered.get(nextIdx).id();
            String nextName = ordered.get(nextIdx).displayName();

            return new FactionRotationInfo(
                    true,
                    hours,
                    computedNowId,
                    computedNowName,
                    nextId,
                    nextName,
                    remaining,
                    nextChange
            );

        } catch (Throwable t) {
            // Fallback safe
            int activeId = 1;
            try { activeId = Math.max(1, repository.getActiveFactionId()); } catch (Throwable ignored) {}
            return FactionRotationInfo.disabled(activeId, "Faction #" + activeId);
        }
    }

    public java.util.List<FactionRotationSlot> getFactionRotationSchedule(int count) {
        int n = Math.max(1, Math.min(count, 48)); // safety: max 48 slots
        try {
            int hours = repository.getFactionRotationHours();
            if (hours <= 0) return java.util.List.of();

            java.util.List<com.matterworks.core.domain.factions.FactionDefinition> factions = repository.loadFactions();
            if (factions == null || factions.isEmpty()) return java.util.List.of();

            java.util.List<com.matterworks.core.domain.factions.FactionDefinition> ordered = new java.util.ArrayList<>(factions);
            ordered.sort(java.util.Comparator
                    .comparingInt(com.matterworks.core.domain.factions.FactionDefinition::sortOrder)
                    .thenComparingInt(com.matterworks.core.domain.factions.FactionDefinition::id));

            long periodMs = (long) hours * 3600_000L;
            if (periodMs <= 0L) periodMs = 3600_000L;

            long nowMs = System.currentTimeMillis();
            long currentSlot = Math.floorDiv(nowMs, periodMs);

            java.util.List<FactionRotationSlot> out = new java.util.ArrayList<>(n);

            for (int i = 0; i < n; i++) {
                long slot = currentSlot + i;
                int idx = (int) Math.floorMod(slot, ordered.size());

                var def = ordered.get(idx);
                long start = slot * periodMs;
                long end = (slot + 1L) * periodMs;

                out.add(new FactionRotationSlot(
                        def.id(),
                        def.displayName(),
                        start,
                        end,
                        Math.max(0L, start - nowMs),
                        Math.max(0L, end - nowMs),
                        i == 0
                ));
            }

            return java.util.List.copyOf(out);

        } catch (Throwable ignored) {
            return java.util.List.of();
        }
    }
    public ServerConfig getServerConfig() {
        return state.getServerConfig();
    }


    public void markPlotDirty(UUID ownerId) {
        GridSaverService s = this.saverService;
        if (s != null && ownerId != null) {
            s.markPlotDirty(ownerId);
        }
    }

    public void addMoney(UUID playerId, double amount, String actionType, String itemId) {
        addMoney(playerId, amount, actionType, itemId, (Integer) null, (Double) null);
    }

    public void addMoney(UUID playerId, double amount, String actionType, String itemId, int factionId) {
        addMoney(playerId, amount, actionType, itemId, Integer.valueOf(factionId), (Double) null);
    }

    void addMoney(UUID playerId, double amount, String actionType, String itemId, Integer factionId, Double value) {
        PlayerProfile p = state.getCachedProfile(playerId);
        if (p == null) return;

        // Update in-memory only (fast, tick-safe)
        p.modifyMoney(amount);
        state.activeProfileCache.put(playerId, p);

        // Mark profile as dirty (will be saved async)
        economyWriter.markProfileDirty(playerId, p);

        // If caller didn't provide value, default to "amount" for MATTER_SELL
        Double valueToLog = value;
        if (valueToLog == null && "MATTER_SELL".equals(actionType)) {
            valueToLog = amount;
        }

        // Log transaction asynchronously + aggregated
        economyWriter.recordTransaction(p, actionType, "MONEY", amount, itemId, factionId, valueToLog);

    }

    public int getPlotHeightCap(UUID ownerId) {
        return world.getPlotHeightCap(ownerId);
    }








    // (optional debug)
    public int getPlacedItemCount(UUID ownerId) { return state.getPlacedItemCount(ownerId); }

    Map<UUID, Map<GridPosition, PlacedMachine>> _unsafeGridsViewForInternal() { return state.playerGrids; }
    Map<UUID, PlotUnlockState> _unsafeUnlockViewForInternal() { return state.plotUnlockCache; }
    Map<UUID, Map<GridPosition, MatterColor>> _unsafeResourcesViewForInternal() { return state.playerResources; }
    Map<UUID, PlayerProfile> _unsafeProfilesViewForInternal() { return state.activeProfileCache; }

    Map<GridPosition, PlacedMachine> _emptyGrid() { return Collections.emptyMap(); }
    Map<GridPosition, PlacedMachine> _copyGrid(Map<GridPosition, PlacedMachine> g) { return g != null ? new HashMap<>(g) : Collections.emptyMap(); }
}
