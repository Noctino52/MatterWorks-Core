package com.matterworks.core.managers;

import com.matterworks.core.common.Direction;
import com.matterworks.core.common.GridPosition;
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

    // ==========================================================
    // GLOBAL OVERCLOCK CACHE (server-wide, real-time)
    // ==========================================================
    private volatile long globalOverclockEndEpochMs = 0L;
    private volatile double globalOverclockMultiplier = 1.0;
    private volatile long globalOverclockLastDurationSeconds = 0L;
    private volatile long globalOverclockLastFetchMs = 0L;

    private final ProductionTelemetry productionTelemetry = new InMemoryProductionTelemetry();



    public GridManager(MariaDBAdapter repository, IWorldAccess worldAdapter, BlockRegistry registry) {
        this.repository = repository;
        this.worldAdapter = worldAdapter;
        this.blockRegistry = registry;

        this.techManager = new TechManager(repository, repository.getTechDefinitionDAO());

        this.state = new GridRuntimeState(repository);
        this.world = new GridWorldService(this, repository, worldAdapter, blockRegistry, techManager, ioExecutor, state);
        this.economy = new GridEconomyService(this, repository, blockRegistry, techManager, ioExecutor, state, world);

        this.marketManager = new MarketManager(this, repository);

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
    public void addMoney(UUID playerId, double amount, String actionType, String itemId) { economy.addMoney(playerId, amount, actionType, itemId); }

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

    public void tick(long t) { world.tick(t); }

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
    // EFFECTIVE MACHINE SPEED (player overclock * global overclock)
    // ==========================================================
    public double getEffectiveMachineSpeedMultiplier(UUID ownerId, String machineTypeId) {
        double machineSpeed = 1.0;
        try {
            machineSpeed = blockRegistry.getSpeed(machineTypeId);
        } catch (Throwable ignored) {}

        if (Double.isNaN(machineSpeed) || Double.isInfinite(machineSpeed) || machineSpeed <= 0.0) {
            machineSpeed = 1.0;
        }

        if (ownerId == null) return machineSpeed;

        var p = state.getCachedProfile(ownerId);
        if (p == null) return machineSpeed;

        long playtime = state.getPlaytimeSecondsCached(ownerId);
        double ocPlayer = p.getActiveOverclockMultiplier(playtime);

        refreshGlobalOverclockCache(false);
        double ocGlobal = getGlobalOverclockMultiplierNow();

        double out = machineSpeed * ocPlayer * ocGlobal;
        if (Double.isNaN(out) || Double.isInfinite(out) || out <= 0.0) return 1.0;

        if (ocPlayer > 1.0 || ocGlobal > 1.0) {
            /*
            System.out.println("[OVERCLOCK] speed multiplier active for owner=" + ownerId
                    + " machine=" + machineTypeId
                    + " machineSpeed=" + machineSpeed
                    + " overclockPlayer=" + ocPlayer
                    + " overclockGlobal=" + ocGlobal
                    + " effective=" + out
                    + " playtime=" + playtime);

             */
        }

        return out;
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

        var soldMatters = snap.getSoldByMatterKey().entrySet().stream()
                .map(e -> new ProductionStatLine(
                        KeyKind.MATTER,
                        e.getKey(),
                        TelemetryKeyFormatter.toLabel(e.getKey()),
                        e.getValue().getQuantity(),
                        e.getValue().getMoneyEarned()
                ))
                .sorted((a, b) -> Double.compare(b.getMoneyEarned(), a.getMoneyEarned()))
                .toList();

        return new ProductionStatsView(
                window,
                producedColors,
                producedMatters,
                consumedColors,
                consumedMatters,
                soldColors,
                soldMatters,
                snap.getTotalProduced(),
                snap.getTotalConsumed(),
                snap.getTotalSoldQuantity(),
                snap.getTotalMoneyEarned()
        );
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
