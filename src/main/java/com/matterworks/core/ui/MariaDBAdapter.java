package com.matterworks.core.ui;

import com.matterworks.core.common.GridPosition;
import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.database.dao.*;
import com.matterworks.core.domain.machines.base.PlacedMachine;
import com.matterworks.core.domain.matter.MatterColor;
import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.domain.shop.VoidShopItem;
import com.matterworks.core.model.PlotObject;
import com.matterworks.core.model.PlotUnlockState;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MariaDBAdapter {

    private final DatabaseManager dbManager;

    private final PlayerDAO playerDAO;
    private final PlotDAO plotDAO;
    private final PlotResourceDAO resourceDAO;
    private final InventoryDAO inventoryDAO;
    private final TechDefinitionDAO techDefinitionDAO;
    private final TransactionDAO transactionDAO;
    private final VoidShopDAO voidShopDAO;

    private final ServerGameStateDAO serverGameStateDAO;
    private final PlayerSessionDAO playerSessionDAO;
    private final PlotMaintenanceDAO plotMaintenanceDAO;
    private final VoidShopPurchaseDAO voidShopPurchaseDAO;

    public MariaDBAdapter(DatabaseManager dbManager) {
        this.dbManager = dbManager;

        this.playerDAO = new PlayerDAO(dbManager);
        this.plotDAO = new PlotDAO(dbManager);
        this.resourceDAO = new PlotResourceDAO(dbManager);
        this.inventoryDAO = new InventoryDAO(dbManager);
        this.techDefinitionDAO = new TechDefinitionDAO(dbManager);
        this.transactionDAO = new TransactionDAO(dbManager);
        this.voidShopDAO = new VoidShopDAO(dbManager);

        this.serverGameStateDAO = new ServerGameStateDAO(dbManager);
        this.playerSessionDAO = new PlayerSessionDAO(dbManager);
        this.plotMaintenanceDAO = new PlotMaintenanceDAO(dbManager);
        this.voidShopPurchaseDAO = new VoidShopPurchaseDAO(dbManager);
    }

    // --- Extra ---
    public Long createPlot(UUID ownerId, int x, int z, int worldId) {
        return plotDAO.createPlot(ownerId, x, z, worldId);
    }

    public DatabaseManager getDbManager() { return dbManager; }

    public TechDefinitionDAO getTechDefinitionDAO() { return techDefinitionDAO; }

    // ==========================================================
    // VOID SHOP
    // ==========================================================
    public List<VoidShopItem> loadVoidShopCatalog() {
        try { return voidShopDAO.loadAll(); }
        catch (Throwable t) { return List.of(); }
    }

    public VoidShopItem loadVoidShopItem(String itemId) {
        if (itemId == null || itemId.isBlank()) return null;
        try { return voidShopDAO.loadById(itemId); }
        catch (Throwable t) { return null; }
    }

    public boolean purchaseVoidShopItemAtomic(UUID playerId, String itemId, int unitPrice, int amount, boolean isAdmin) {
        return voidShopPurchaseDAO.purchaseVoidShopItemAtomic(playerId, itemId, unitPrice, amount, isAdmin);
    }

    // ==========================================================
    // CAP PLOT ITEMS
    // ==========================================================
    public int getDefaultItemPlacedOnPlotCap() {
        return serverGameStateDAO.getDefaultItemPlacedOnPlotCap();
    }

    public int getPlotItemsPlaced(UUID ownerId) {
        return plotMaintenanceDAO.getPlotItemsPlaced(ownerId);
    }

    public int getItemCapIncreaseStep() {
        return serverGameStateDAO.getItemCapIncreaseStep();
    }

    public int getMaxItemPlacedOnPlotCap() {
        return serverGameStateDAO.getMaxItemPlacedOnPlotCap();
    }

    public void updateMaxItemPlacedOnPlotCap(int newCap) {
        serverGameStateDAO.updateMaxItemPlacedOnPlotCap(newCap);
    }

    // ✅ NEW: reads server_gamestate.itemcap_void_increase_step (compat fallback handled in DAO)
    public int getVoidItemCapIncreaseStep() {
        return serverGameStateDAO.getVoidItemCapIncreaseStep();
    }

    // ✅ NEW: persisted per-plot bonus on plots.void_itemcap_extra
    public int getPlotVoidItemCapExtra(UUID ownerId) {
        return plotDAO.getVoidItemCapExtra(ownerId);
    }

    public int addPlotVoidItemCapExtra(UUID ownerId, int delta) {
        return plotDAO.addVoidItemCapExtra(ownerId, delta);
    }

    // ==========================================================
    // TRANSACTIONS
    // ==========================================================
    public void logTransaction(PlayerProfile player, String actionType, String currency, double amount, String itemId) {
        transactionDAO.logTransaction(player, actionType, currency, BigDecimal.valueOf(amount), itemId);
    }

    // ==========================================================
    // CONFIG
    // ==========================================================
    public ServerConfig loadServerConfig() {
        return serverGameStateDAO.loadServerConfig();
    }

    public int loadMinutesToInactive() {
        return serverGameStateDAO.loadMinutesToInactive();
    }

    // ==========================================================
    // Player Sessions
    // ==========================================================
    public void openPlayerSession(UUID playerUuid) { playerSessionDAO.openPlayerSession(playerUuid); }
    public void closePlayerSession(UUID playerUuid) { playerSessionDAO.closePlayerSession(playerUuid); }

    // ==========================================================
    // PLAYER & PROFILE
    // ==========================================================
    public PlayerProfile loadPlayerProfile(UUID uuid) { return playerDAO.load(uuid); }
    public void savePlayerProfile(PlayerProfile profile) { playerDAO.save(profile); }
    public List<PlayerProfile> getAllPlayers() { return playerDAO.loadAll(); }
    public void deletePlayerFull(UUID uuid) { plotMaintenanceDAO.deletePlayerFull(uuid); }

    // ==========================================================
    // INVENTORY
    // ==========================================================
    public int getInventoryItemCount(UUID ownerId, String itemId) {
        return inventoryDAO.getItemCount(ownerId, itemId);
    }

    public void modifyInventoryItem(UUID ownerId, String itemId, int delta) {
        inventoryDAO.modifyItemCount(ownerId, itemId, delta);
    }

    // ==========================================================
    // PLOT / MACHINES
    // ==========================================================
    public List<PlotObject> loadPlotMachines(UUID ownerId) {
        return plotDAO.loadMachines(ownerId);
    }

    public Long createMachine(UUID ownerId, PlacedMachine machine) {
        String jsonMeta = machine.serialize().toString();
        return plotDAO.insertMachine(
                ownerId,
                machine.getTypeId(),
                machine.getPos().x(), machine.getPos().y(), machine.getPos().z(),
                jsonMeta
        );
    }

    public void deleteMachine(Long dbId) { plotDAO.removeMachine(dbId); }

    public void updateMachinesMetadata(List<PlacedMachine> machines) {
        plotMaintenanceDAO.updateMachinesMetadata(machines);
    }

    public void clearPlotData(UUID ownerId) { plotMaintenanceDAO.clearPlotData(ownerId); }

    public Long getPlotId(UUID ownerId) { return plotDAO.findPlotIdByOwner(ownerId); }

    // ==========================================================
    // RESOURCES
    // ==========================================================
    public void saveResource(Long plotId, int x, int z, MatterColor type) {
        resourceDAO.addResource(plotId, x, z, type);
    }

    public Map<GridPosition, MatterColor> loadResources(Long plotId) {
        return resourceDAO.loadResources(plotId);
    }

    public int getVoidPlotItemBreakerIncreased() {
        return serverGameStateDAO.getVoidPlotItemBreakerIncreased();
    }

    public int addVoidPlotItemBreakerIncreased(int delta) {
        return serverGameStateDAO.addVoidPlotItemBreakerIncreased(delta);
    }

    // ==========================================================
    // PLOT UNLOCK
    // ==========================================================
    public PlotUnlockState loadPlotUnlockState(UUID ownerId) { return plotDAO.loadPlotUnlockState(ownerId); }
    public boolean updatePlotUnlockState(UUID ownerId, PlotUnlockState state) { return plotDAO.updatePlotUnlockState(ownerId, state); }
}
