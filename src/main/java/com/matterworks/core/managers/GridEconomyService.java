package com.matterworks.core.managers;

import com.matterworks.core.domain.machines.registry.BlockRegistry;
import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.domain.shop.VoidShopItem;
import com.matterworks.core.model.PlotUnlockState;
import com.matterworks.core.ui.MariaDBAdapter;
import com.matterworks.core.ui.ServerConfig;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

final class GridEconomyService {

    private final GridManager gridManager;
    private final MariaDBAdapter repository;
    private final BlockRegistry blockRegistry;
    private final TechManager techManager;
    private final ExecutorService ioExecutor;
    private final GridRuntimeState state;
    private final GridWorldService world;

    private static final String ITEM_OVERCLOCK_2H = "overclock_2h";
    private static final String ITEM_OVERCLOCK_12H = "overclock_12h";
    private static final String ITEM_OVERCLOCK_24H = "overclock_24h";
    private static final String ITEM_OVERCLOCK_LIFE = "overclock_life";

    private static final String ITEM_GLOBAL_OVERCLOCK_2H = "global_overclock_2h";
    private static final String ITEM_GLOBAL_OVERCLOCK_12H = "global_overclock_12h";
    private static final String ITEM_GLOBAL_OVERCLOCK_24H = "global_overclock_24h";

    GridEconomyService(
            GridManager gridManager,
            MariaDBAdapter repository,
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

    boolean canUseOverclock(UUID ownerId, String itemId) {
        if (ownerId == null || itemId == null || itemId.isBlank()) return false;

        var p = state.getCachedProfile(ownerId);
        if (p != null && p.isAdmin()) return true;

        int have = repository.getInventoryItemCount(ownerId, itemId);
        return have > 0;
    }

    boolean useOverclock(UUID ownerId, String itemId) {
        if (ownerId == null || itemId == null || itemId.isBlank()) return false;

        state.touchPlayer(ownerId);

        var cached = state.getCachedProfile(ownerId);
        if (cached == null) return false;

        boolean admin = cached.isAdmin();

        if (!admin) {
            int have = repository.getInventoryItemCount(ownerId, itemId);
            if (have <= 0) return false;
        }

        // Route GLOBAL overclocks here (so UI can keep calling useOverclock)
        if (isGlobalOverclockItem(itemId)) {
            return applyGlobalOverclock(ownerId, itemId, admin);
        }

        long durationSeconds = switch (itemId) {
            case ITEM_OVERCLOCK_2H -> 2L * 3600L;
            case ITEM_OVERCLOCK_12H -> 12L * 3600L;
            case ITEM_OVERCLOCK_24H -> 24L * 3600L;
            case ITEM_OVERCLOCK_LIFE -> -1L;
            default -> 0L;
        };

        if (durationSeconds == 0L) return false;

        long playtimeNow = state.getPlaytimeSecondsCached(ownerId);

        // load fresh from DB to avoid cache desync
        var p = repository.loadPlayerProfile(ownerId);
        if (p == null) return false;

        p.setOverclockStartPlaytimeSeconds(playtimeNow);
        p.setOverclockDurationSeconds(durationSeconds);
        p.setOverclockMultiplier(2.0);

        repository.savePlayerProfile(p);

        // refresh caches
        state.activeProfileCache.put(ownerId, p);

        if (!admin) {
            repository.modifyInventoryItem(ownerId, itemId, -1);
            repository.logTransaction(p, "VOID_SHOP_USE", "OVERCLOCK", 1.0, itemId);
        } else {
            repository.logTransaction(p, "VOID_SHOP_USE_ADMIN", "OVERCLOCK", 0.0, itemId);
        }

        /*
        System.out.println("[OVERCLOCK] Applied item=" + itemId
                + " owner=" + ownerId
                + " startPlaytime=" + playtimeNow
                + " durationSeconds=" + durationSeconds
                + " multiplier=2.0");
         */

        return true;
    }

    private boolean isGlobalOverclockItem(String itemId) {
        if (itemId == null) return false;
        return itemId.equals(ITEM_GLOBAL_OVERCLOCK_2H)
                || itemId.equals(ITEM_GLOBAL_OVERCLOCK_12H)
                || itemId.equals(ITEM_GLOBAL_OVERCLOCK_24H);
    }

    private boolean applyGlobalOverclock(UUID activatorId, String itemId, boolean admin) {
        long durationSeconds = switch (itemId) {
            case ITEM_GLOBAL_OVERCLOCK_2H -> 2L * 3600L;
            case ITEM_GLOBAL_OVERCLOCK_12H -> 12L * 3600L;
            case ITEM_GLOBAL_OVERCLOCK_24H -> 24L * 3600L;
            default -> 0L;
        };

        if (durationSeconds <= 0L) return false;

        long nowMs = System.currentTimeMillis();

        // Read fresh from DB to be safe (so multiple uses stack correctly)
        long currentEndMs = 0L;
        double currentMult = 1.0;
        try {
            currentEndMs = Math.max(0L, repository.getGlobalOverclockEndEpochMs());
            currentMult = repository.getGlobalOverclockMultiplier();
        } catch (Throwable ignored) {}

        if (Double.isNaN(currentMult) || Double.isInfinite(currentMult) || currentMult <= 0.0) currentMult = 1.0;

        // Extend if already active, otherwise start now
        long baseMs = Math.max(nowMs, currentEndMs);
        long newEndMs = baseMs + durationSeconds * 1000L;

        double newMult = 2.0;

        // Persist
        repository.setGlobalOverclockState(newEndMs, newMult, durationSeconds);

        // Update GridManager cache (no need to touch GridRuntimeState)
        gridManager._setGlobalOverclockStateCached(newEndMs, newMult, durationSeconds);

        PlayerProfile p = repository.loadPlayerProfile(activatorId);
        if (p == null) p = state.getCachedProfile(activatorId);

        if (!admin) {
            repository.modifyInventoryItem(activatorId, itemId, -1);
            if (p != null) repository.logTransaction(p, "VOID_SHOP_USE", "GLOBAL_OVERCLOCK", 1.0, itemId);
        } else {
            if (p != null) repository.logTransaction(p, "VOID_SHOP_USE_ADMIN", "GLOBAL_OVERCLOCK", 0.0, itemId);
        }

        System.out.println("[GLOBAL_OVERCLOCK] Applied item=" + itemId
                + " by=" + activatorId
                + " nowMs=" + nowMs
                + " prevEndMs=" + currentEndMs
                + " newEndMs=" + newEndMs
                + " durationSeconds=" + durationSeconds
                + " multiplier=" + newMult);

        return true;
    }

    boolean buyVoidShopItem(UUID playerId, String premiumItemId, int amount) {
        state.touchPlayer(playerId);

        if (playerId == null || premiumItemId == null || premiumItemId.isBlank() || amount <= 0) return false;

        PlayerProfile p = state.getCachedProfile(playerId);
        if (p == null) return false;

        VoidShopItem def = repository.loadVoidShopItem(premiumItemId);
        if (def == null) return false;

        int unit = Math.max(0, def.voidPrice());

        // always atomic path (MariaDB-only)
        boolean ok = repository.purchaseVoidShopItemAtomic(playerId, premiumItemId, unit, amount, p.isAdmin());
        if (!ok) return false;

        // reload profile to update void coins in cache/UI
        PlayerProfile fresh = repository.loadPlayerProfile(playerId);
        if (fresh != null) state.activeProfileCache.put(playerId, fresh);

        if (!p.isAdmin()) {
            repository.logTransaction(fresh != null ? fresh : p, "VOID_SHOP_BUY", "VOID_COINS", (double) unit * amount, premiumItemId);
        } else {
            repository.logTransaction(fresh != null ? fresh : p, "VOID_SHOP_BUY_ADMIN", "VOID_COINS", 0, premiumItemId);
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
            // save states but do NOT reset DB
            world.saveAndUnloadSpecific(ownerId);

            // consume premium item
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

            // reload to update UI/world
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
            ensureInventoryAtLeast(ownerId, "drill", 1);
            ensureInventoryAtLeast(ownerId, "nexus_core", 1);

            world.loadPlotSynchronously(ownerId);
        });
    }

    void prestigeUser(UUID ownerId) {
        if (ownerId == null) return;
        state.touchPlayer(ownerId);

// Charge prestige action cost (player only)
        try {
            PlayerProfile p = repository.loadPlayerProfile(ownerId);
            if (p != null && !p.isAdmin()) {
                double cost = getPrestigeActionCost(p);
                if (p.getMoney() < cost) {
                    System.out.println("[PRESTIGE] Denied (race): money changed owner=" + ownerId);
                    return;
                }

                p.modifyMoney(-cost);
                repository.savePlayerProfile(p);
                repository.logTransaction(p, "PRESTIGE_PAY", "MONEY", -cost, "prestige_action_fee");
                state.activeProfileCache.put(ownerId, p);
            }
        } catch (Throwable t) {
            t.printStackTrace();
            return;
        }


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
            ensureInventoryAtLeast(ownerId, "drill", 1);
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

    boolean unlockTechNode(UUID playerId, String nodeId) {
        if (playerId == null || nodeId == null || nodeId.isBlank()) return false;

        state.touchPlayer(playerId);

        PlayerProfile p = state.getCachedProfile(playerId);
        if (p == null) return false;

        boolean ok = techManager.unlockNode(p, nodeId);
        if (!ok) return false;

        // techManager.unlockNode already saves the profile and logs transaction
        // We refresh the cache anyway to keep UI consistent.
        state.activeProfileCache.put(playerId, p);
        return true;
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

        repository.modifyInventoryItem(newUuid, "drill", 1);
        repository.modifyInventoryItem(newUuid, "nexus_core", 1);
        repository.modifyInventoryItem(newUuid, "conveyor_belt", 10);

        repository.createPlot(newUuid, 1, 0, 0);

        world.preloadPlotFromDB(newUuid);
        return p;
    }

    void deletePlayer(UUID uuid) {
        if (uuid == null) return;

        repository.closePlayerSession(uuid);

        synchronized (state.tickingMachines) {
            world.saveAndUnloadSpecific(uuid);
            state.activeProfileCache.remove(uuid);
        }
        repository.deletePlayerFull(uuid);
    }

    double getPrestigeActionCost(PlayerProfile p) {
        if (p == null) return 0.0;

        state.reloadServerConfig();
        ServerConfig cfg = state.getServerConfig();

        double base = Math.max(0.0, cfg.prestigeActionCostBase());
        double mult = Math.max(0.0, cfg.prestigeActionCostMult());

        int prestige = Math.max(0, p.getPrestigeLevel());
        double factor = 1.0 + (prestige * mult);

        double out = base * factor;
        if (Double.isNaN(out) || Double.isInfinite(out)) return base;

        return Math.max(0.0, out);
    }

    boolean canPerformPrestige(UUID ownerId) {
        if (ownerId == null) return false;

        PlayerProfile p = state.getCachedProfile(ownerId);
        if (p == null) return false;

        // Must have prestige tech unlocked even for admin
        if (!techManager.isPrestigeUnlocked(p)) return false;

        if (p.isAdmin()) return true;

        double cost = getPrestigeActionCost(p);
        return p.getMoney() >= cost;
    }


}
