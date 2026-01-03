package com.matterworks.core.managers;

import com.matterworks.core.common.Direction;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.domain.machines.base.PlacedMachine;
import com.matterworks.core.domain.machines.registry.BlockRegistry;
import com.matterworks.core.domain.matter.MatterColor;
import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.domain.shop.MarketManager;
import com.matterworks.core.domain.shop.VoidShopItem;
import com.matterworks.core.model.PlotUnlockState;
import com.matterworks.core.ports.IWorldAccess;
import com.matterworks.core.ui.MariaDBAdapter;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GridManager {

    public static final String ITEM_INSTANT_PRESTIGE = "instant_prestige";

    private final MariaDBAdapter repository;
    private final IWorldAccess worldAdapter;
    private final BlockRegistry blockRegistry;

    private final ExecutorService ioExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final TechManager techManager;
    private final MarketManager marketManager;

    private final GridRuntimeState state;
    private final GridWorldService world;
    private final GridEconomyService economy;

    public GridManager(MariaDBAdapter repository, IWorldAccess worldAdapter, BlockRegistry registry) {
        this.repository = repository;
        this.worldAdapter = worldAdapter;
        this.blockRegistry = registry;

        this.techManager = new TechManager(repository, repository.getTechDefinitionDAO());

        this.state = new GridRuntimeState(repository);
        this.world = new GridWorldService(this, repository, worldAdapter, blockRegistry, techManager, ioExecutor, state);
        this.economy = new GridEconomyService(this, repository, blockRegistry, techManager, ioExecutor, state, world);

        this.marketManager = new MarketManager(this, repository);
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
    public boolean increasePlotUnlockedArea(UUID ownerId) { return world.increasePlotUnlockedArea(ownerId); }
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
    // ✅ PERSISTENT "+" MECHANIC
    // plots.void_itemcap_extra += server_gamestate.itemcap_void_increase_step
    // ==========================================================
    public boolean increaseMaxItemPlacedOnPlotCap(UUID requesterId) {
        if (requesterId == null) return false;

        state.touchPlayer(requesterId);

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
        return after > before;
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
