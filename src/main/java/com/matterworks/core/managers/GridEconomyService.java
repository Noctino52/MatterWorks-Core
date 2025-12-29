// FILE: src/main/java/com/matterworks/core/managers/GridEconomyService.java
package com.matterworks.core.managers;

import com.matterworks.core.domain.machines.registry.BlockRegistry;
import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.domain.shop.VoidShopItem;
import com.matterworks.core.model.PlotUnlockState;
import com.matterworks.core.ports.IRepository;
import com.matterworks.core.ui.MariaDBAdapter;
import com.matterworks.core.ui.ServerConfig;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

final class GridEconomyService {

    private final GridManager gridManager;
    private final IRepository repository;
    private final BlockRegistry blockRegistry;
    private final TechManager techManager;
    private final ExecutorService ioExecutor;
    private final GridRuntimeState state;
    private final GridWorldService world;

    GridEconomyService(
            GridManager gridManager,
            IRepository repository,
            BlockRegistry blockRegistry,
            TechManager techManager,
            ExecutorService ioExecutor,
            GridRuntimeState state,
            GridWorldService world
    ) {
        this.gridManager = gridManager;
        this.repository = repository;
        this.blockRegistry = blockRegistry;
        this.techManager = techManager;
        this.ioExecutor = ioExecutor;
        this.state = state;
        this.world = world;
    }

    // ==========================================================
    // VOID SHOP API
    // ==========================================================
    List<VoidShopItem> getVoidShopCatalog() {
        return repository.loadVoidShopCatalog();
    }

    boolean buyVoidShopItem(UUID playerId, String premiumItemId, int amount) {
        state.touchPlayer(playerId);

        if (playerId == null || premiumItemId == null || premiumItemId.isBlank() || amount <= 0) return false;

        PlayerProfile p = state.getCachedProfile(playerId);
        if (p == null) return false;

        VoidShopItem def = repository.loadVoidShopItem(premiumItemId);
        if (def == null) return false;

        int unit = Math.max(0, def.voidPrice());

        // ✅ path atomico per MariaDB (niente più coin persi se inventario fallisce)
        if (repository instanceof MariaDBAdapter db) {
            boolean ok = db.purchaseVoidShopItemAtomic(playerId, premiumItemId, unit, amount, p.isAdmin());
            if (!ok) return false;

            // ricarica profilo per aggiornare void coins in cache/UI
            PlayerProfile fresh = repository.loadPlayerProfile(playerId);
            if (fresh != null) state.activeProfileCache.put(playerId, fresh);

            if (!p.isAdmin()) {
                repository.logTransaction(fresh != null ? fresh : p, "VOID_SHOP_BUY", "VOID_COINS", (double) unit * amount, premiumItemId);
            } else {
                repository.logTransaction(fresh != null ? fresh : p, "VOID_SHOP_BUY_ADMIN", "VOID_COINS", 0, premiumItemId);
            }
            return true;
        }

        // fallback
        long totalL = (long) unit * (long) amount;
        if (totalL > Integer.MAX_VALUE) totalL = Integer.MAX_VALUE;
        int total = (int) totalL;

        if (!p.isAdmin() && p.getVoidCoins() < total) return false;

        try {
            repository.modifyInventoryItem(playerId, premiumItemId, amount);
        } catch (Throwable t) {
            t.printStackTrace();
            return false;
        }

        if (!p.isAdmin()) {
            p.modifyVoidCoins(-total);
            repository.savePlayerProfile(p);
            state.activeProfileCache.put(playerId, p);
            repository.logTransaction(p, "VOID_SHOP_BUY", "VOID_COINS", total, premiumItemId);
        } else {
            repository.logTransaction(p, "VOID_SHOP_BUY_ADMIN", "VOID_COINS", 0, premiumItemId);
        }

        return true;
    }

    boolean canInstantPrestige(UUID ownerId) {
        if (ownerId == null) return false;
        PlayerProfile p = state.getCachedProfile(ownerId);
        if (p != null && p.isAdmin()) return true;
        return repository.getInventoryItemCount(ownerId, GridManager.ITEM_INSTANT_PRESTIGE) > 0;
    }

    void instantPrestigeUser(UUID ownerId) {
        if (ownerId == null) return;
        state.touchPlayer(ownerId);

        PlayerProfile cached = state.getCachedProfile(ownerId);
        if (cached == null) return;

        if (!cached.isAdmin()) {
            int have = repository.getInventoryItemCount(ownerId, GridManager.ITEM_INSTANT_PRESTIGE);
            if (have <= 0) return;
        }

        state.reloadServerConfig();
        final ServerConfig serverConfig = state.getServerConfig();
        final int addVoidCoins = Math.max(0, serverConfig.prestigeVoidCoinsAdd());
        final int plotBonus = Math.max(0, serverConfig.prestigePlotBonus());

        ioExecutor.submit(() -> {
            // salva stati ma NON resetta DB
            world.saveAndUnloadSpecific(ownerId);

            // consuma item premium
            try {
                PlayerProfile p = repository.loadPlayerProfile(ownerId);
                if (p == null) return;

                if (!p.isAdmin()) {
                    int have = repository.getInventoryItemCount(ownerId, GridManager.ITEM_INSTANT_PRESTIGE);
                    if (have <= 0) return;

                    repository.modifyInventoryItem(ownerId, GridManager.ITEM_INSTANT_PRESTIGE, -1);
                    repository.logTransaction(p, "INSTANT_PRESTIGE_USE", "ITEM", 1, GridManager.ITEM_INSTANT_PRESTIGE);
                }
            } catch (Throwable t) {
                t.printStackTrace();
                return;
            }

            // plot bonus (unlocked_extra_x/y)
            try {
                PlotUnlockState cur = repository.loadPlotUnlockState(ownerId);
                if (cur == null) cur = PlotUnlockState.zero();

                PlotUnlockState upd = new PlotUnlockState(
                        cur.extraX() + plotBonus,
                        cur.extraY() + plotBonus
                );
                repository.updatePlotUnlockState(ownerId, upd);
            } catch (Throwable t) {
                t.printStackTrace();
            }

            // prestige + void coins (NO reset)
            try {
                PlayerProfile p = repository.loadPlayerProfile(ownerId);
                if (p != null) {
                    p.setPrestigeLevel(p.getPrestigeLevel() + 1);
                    if (addVoidCoins > 0) p.modifyVoidCoins(addVoidCoins);

                    repository.savePlayerProfile(p);
                    state.activeProfileCache.put(ownerId, p);

                    repository.logTransaction(p, "INSTANT_PRESTIGE", "NONE", 0, "prestige+1");
                    if (addVoidCoins > 0) {
                        repository.logTransaction(p, "INSTANT_PRESTIGE_REWARD", "VOID_COINS", addVoidCoins, "prestige_reward");
                    }
                }
            } catch (Throwable t) {
                t.printStackTrace();
            }

            // ricarica per aggiornare UI/mondo
            world.loadPlotSynchronously(ownerId);
        });
    }

    // ==========================================================
    // PROFILES / MONEY
    // ==========================================================
    void addMoney(UUID playerId, double amount, String actionType, String itemId) {
        PlayerProfile p = state.getCachedProfile(playerId);
        if (p == null) return;
        p.modifyMoney(amount);
        repository.savePlayerProfile(p);
        repository.logTransaction(p, actionType, "MONEY", amount, itemId);
        state.activeProfileCache.put(playerId, p);
    }

    // ==========================================================
    // SHOP / ECONOMY
    // ==========================================================
    boolean buyItem(UUID playerId, String itemId, int amount) {
        state.touchPlayer(playerId);

        PlayerProfile p = state.getCachedProfile(playerId);
        if (p == null) return false;

        if (!p.isAdmin() && "nexus_core".equals(itemId)) return false;

        if (!techManager.canBuyItem(p, itemId)) return false;
        if (!p.isAdmin() && !state.checkItemCap(playerId, itemId, amount)) return false;

        double unit = getEffectiveShopUnitPrice(p, itemId);
        double cost = unit * amount;

        if (!p.isAdmin() && p.getMoney() < cost) return false;

        if (!p.isAdmin()) addMoney(playerId, -cost, "ITEM_BUY", itemId);
        repository.modifyInventoryItem(playerId, itemId, amount);
        return true;
    }

    double getEffectiveShopUnitPrice(UUID playerId, String itemId) {
        PlayerProfile p = state.getCachedProfile(playerId);
        return getEffectiveShopUnitPrice(p, itemId);
    }

    double getEffectiveShopUnitPrice(PlayerProfile p, String itemId) {
        if (itemId == null) return 0.0;

        var stats = blockRegistry.getStats(itemId);
        double base = (stats != null ? stats.basePrice() : 0.0);
        double mult = (stats != null ? Math.max(0.0, stats.prestigeCostMult()) : 0.0);

        int prestige = (p != null ? Math.max(0, p.getPrestigeLevel()) : 0);

        double factor = 1.0 + (prestige * mult);
        double out = base * factor;

        if (Double.isNaN(out) || Double.isInfinite(out)) return base;
        return Math.max(0.0, out);
    }

    // ==========================================================
    // SOS
    // ==========================================================
    boolean attemptBailout(UUID ownerId) {
        state.touchPlayer(ownerId);

        PlayerProfile p = state.getCachedProfile(ownerId);
        if (p == null) return false;

        state.reloadServerConfig();
        ServerConfig serverConfig = state.getServerConfig();

        if (p.getMoney() < serverConfig.sosThreshold()) {
            double diff = serverConfig.sosThreshold() - p.getMoney();
            addMoney(ownerId, diff, "SOS_USE", "bailout");
            return true;
        }
        return false;
    }

    // ==========================================================
    // RESET / PRESTIGE CLASSICO
    // ==========================================================
    void resetUserPlot(UUID ownerId) {
        if (ownerId == null) return;
        state.touchPlayer(ownerId);

        state.reloadServerConfig();
        final double startMoney = state.getServerConfig().startMoney();

        ioExecutor.submit(() -> {
            world.saveAndUnloadSpecific(ownerId);

            repository.clearPlotData(ownerId);

            try {
                PlayerProfile p = repository.loadPlayerProfile(ownerId);
                if (p != null) {
                    p.setMoney(startMoney);
                    p.resetTechTreeToDefaults();
                    repository.savePlayerProfile(p);
                    state.activeProfileCache.put(ownerId, p);
                }
            } catch (Throwable t) {
                t.printStackTrace();
            }

            ensureInventoryAtLeast(ownerId, "conveyor_belt", 10);
            ensureInventoryAtLeast(ownerId, "drill_mk1", 1);
            ensureInventoryAtLeast(ownerId, "nexus_core", 1);

            world.loadPlotSynchronously(ownerId);
        });
    }

    void prestigeUser(UUID ownerId) {
        if (ownerId == null) return;
        state.touchPlayer(ownerId);

        state.reloadServerConfig();
        final ServerConfig serverConfig = state.getServerConfig();
        final double startMoney = serverConfig.startMoney();
        final int addVoidCoins = Math.max(0, serverConfig.prestigeVoidCoinsAdd());
        final int plotBonus = Math.max(0, serverConfig.prestigePlotBonus());

        ioExecutor.submit(() -> {
            world.saveAndUnloadSpecific(ownerId);

            repository.clearPlotData(ownerId);

            try {
                PlotUnlockState cur = repository.loadPlotUnlockState(ownerId);
                if (cur == null) cur = PlotUnlockState.zero();
                PlotUnlockState upd = new PlotUnlockState(
                        cur.extraX() + plotBonus,
                        cur.extraY() + plotBonus
                );
                repository.updatePlotUnlockState(ownerId, upd);
            } catch (Throwable t) {
                t.printStackTrace();
            }

            try {
                PlayerProfile p = repository.loadPlayerProfile(ownerId);
                if (p != null) {
                    p.setPrestigeLevel(p.getPrestigeLevel() + 1);
                    if (addVoidCoins > 0) p.modifyVoidCoins(addVoidCoins);

                    p.setMoney(startMoney);
                    p.resetTechTreeToDefaults();
                    repository.savePlayerProfile(p);
                    state.activeProfileCache.put(ownerId, p);
                }
            } catch (Throwable t) {
                t.printStackTrace();
            }

            ensureInventoryAtLeast(ownerId, "conveyor_belt", 10);
            ensureInventoryAtLeast(ownerId, "drill_mk1", 1);
            ensureInventoryAtLeast(ownerId, "nexus_core", 1);

            world.loadPlotSynchronously(ownerId);
        });
    }

    private void ensureInventoryAtLeast(UUID ownerId, String itemId, int target) {
        if (ownerId == null || itemId == null || target <= 0) return;

        int cur;
        try { cur = repository.getInventoryItemCount(ownerId, itemId); }
        catch (Throwable t) { cur = 0; }

        if (cur < target) repository.modifyInventoryItem(ownerId, itemId, target - cur);
    }

    // ==========================================================
    // PLAYER MGMT
    // ==========================================================
    PlayerProfile createNewPlayer(String username) {
        state.reloadServerConfig();
        ServerConfig serverConfig = state.getServerConfig();

        UUID newUuid = UUID.randomUUID();
        PlayerProfile p = new PlayerProfile(newUuid);
        p.setUsername(username);
        p.setMoney(serverConfig.startMoney());
        p.setRank(PlayerProfile.PlayerRank.PLAYER);

        repository.savePlayerProfile(p);
        state.activeProfileCache.put(newUuid, p);

        repository.modifyInventoryItem(newUuid, "drill_mk1", 1);
        repository.modifyInventoryItem(newUuid, "nexus_core", 1);
        repository.modifyInventoryItem(newUuid, "conveyor_belt", 10);

        if (repository instanceof MariaDBAdapter db) {
            db.createPlot(newUuid, 1, 0, 0);
        }

        world.preloadPlotFromDB(newUuid);
        return p;
    }

    void deletePlayer(UUID uuid) {
        if (uuid == null) return;

        if (repository instanceof MariaDBAdapter a) a.closePlayerSession(uuid);

        synchronized (state.tickingMachines) {
            world.saveAndUnloadSpecific(uuid);
            state.activeProfileCache.remove(uuid);
        }
        repository.deletePlayerFull(uuid);
    }
}
