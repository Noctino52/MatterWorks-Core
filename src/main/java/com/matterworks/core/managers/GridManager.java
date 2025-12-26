package com.matterworks.core.managers;

import com.matterworks.core.common.Direction;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.domain.factory.MachineFactory;
import com.matterworks.core.domain.machines.base.PlacedMachine;
import com.matterworks.core.domain.machines.production.DrillMachine;
import com.matterworks.core.domain.machines.registry.BlockRegistry;
import com.matterworks.core.domain.machines.structure.StructuralBlock;
import com.matterworks.core.domain.matter.MatterColor;
import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.domain.shop.MarketManager;
import com.matterworks.core.ui.MariaDBAdapter;
import com.matterworks.core.ui.ServerConfig;
import com.matterworks.core.model.PlotObject;
import com.matterworks.core.model.PlotUnlockState;
import com.matterworks.core.ports.IRepository;
import com.matterworks.core.ports.IWorldAccess;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class GridManager {

    private final IRepository repository;
    private final IWorldAccess worldAdapter;
    private final BlockRegistry blockRegistry;

    private final MarketManager marketManager;
    private final TechManager techManager;

    private final ExecutorService ioExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private volatile ServerConfig serverConfig;

    // --- RUNTIME WORLD STATE ---
    private final Map<UUID, PlayerProfile> activeProfileCache = new ConcurrentHashMap<>();
    private final Map<UUID, Map<GridPosition, PlacedMachine>> playerGrids = new ConcurrentHashMap<>();
    private final Map<UUID, Map<GridPosition, MatterColor>> playerResources = new ConcurrentHashMap<>();
    // Plot unlock state (extra unlocked cells vs starting)
    private final Map<UUID, PlotUnlockState> plotUnlockCache = new ConcurrentHashMap<>();

    private final List<PlacedMachine> tickingMachines = Collections.synchronizedList(new ArrayList<>());

    // --- ACTIVITY / SLEEPING ---
    private final Map<UUID, Long> lastActivityMs = new ConcurrentHashMap<>();
    private final Set<UUID> sleepingPlayers = ConcurrentHashMap.newKeySet();
    private volatile int minutesToInactive = 5;

    // --- MAINTENANCE ---
    private long lastSweepTick = 0;

    public GridManager(IRepository repository, IWorldAccess worldAdapter, BlockRegistry registry) {
        this.repository = repository;
        this.worldAdapter = worldAdapter;
        this.blockRegistry = registry;

        this.marketManager = new MarketManager(this, repository);
        this.techManager = new TechManager(repository, (repository instanceof MariaDBAdapter a) ? a.getTechDefinitionDAO() : null);

        this.serverConfig = repository.loadServerConfig();
        reloadMinutesToInactive();
    }

    // ==========================================================
    // GETTERS
    // ==========================================================
    public TechManager getTechManager() { return techManager; }
    public MarketManager getMarketManager() { return marketManager; }
    public BlockRegistry getBlockRegistry() { return blockRegistry; }

    // ==========================================================
    // SNAPSHOTS (GUI)
    // ==========================================================
    public Map<GridPosition, PlacedMachine> getSnapshot(UUID ownerId) {
        Map<GridPosition, PlacedMachine> g = playerGrids.get(ownerId);
        return g != null ? new HashMap<>(g) : Collections.emptyMap();
    }

    public Map<GridPosition, PlacedMachine> getAllMachinesSnapshot() {
        Map<GridPosition, PlacedMachine> all = new HashMap<>();
        for (var map : playerGrids.values()) all.putAll(map);
        return all;
    }

    public Map<GridPosition, MatterColor> getTerrainResources(UUID playerId) {
        return playerResources.getOrDefault(playerId, Collections.emptyMap());
    }


    // ==========================================================
    // PLOT AREA (unlock / bounds)
    // ==========================================================
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

    public PlotAreaInfo getPlotAreaInfo(UUID ownerId) {
        ServerConfig cfg = (serverConfig != null) ? serverConfig : repository.loadServerConfig();
        PlotUnlockState st = plotUnlockCache.getOrDefault(ownerId, PlotUnlockState.zero());

        int startX = Math.max(1, cfg.plotStartingX());
        int startY = Math.max(1, cfg.plotStartingY());
        int maxX = Math.max(startX, cfg.plotMaxX());
        int maxY = Math.max(startY, cfg.plotMaxY());
        int incX = Math.max(1, cfg.plotIncreaseX());
        int incY = Math.max(1, cfg.plotIncreaseY());

        // clamp stored extras (in case config changed)
        int extraX = Math.max(0, Math.min(st.extraX(), maxX - startX));
        int extraY = Math.max(0, Math.min(st.extraY(), maxY - startY));

        return new PlotAreaInfo(startX, startY, maxX, maxY, incX, incY, extraX, extraY);
    }

    private boolean isCellWithinMax(UUID ownerId, int gx, int gz) {
        PlotAreaInfo info = getPlotAreaInfo(ownerId);
        return gx >= 0 && gx < info.maxX() && gz >= 0 && gz < info.maxY();
    }

    private boolean isCellUnlocked(UUID ownerId, int gx, int gz) {
        PlotAreaInfo info = getPlotAreaInfo(ownerId);
        return gx >= info.minX() && gx < info.maxXExclusive()
                && gz >= info.minZ() && gz < info.maxZExclusive();
    }

    private boolean isWithinPlotBounds(UUID ownerId, GridPosition origin, Vector3Int size) {
        if (origin == null || size == null) return false;
        PlotAreaInfo info = getPlotAreaInfo(ownerId);

        int sx = Math.max(1, size.x());
        int sz = Math.max(1, size.z());

        int minX = origin.x();
        int minZ = origin.z();
        int maxXEx = origin.x() + sx;
        int maxZEx = origin.z() + sz;

        return minX >= 0 && minZ >= 0 && maxXEx <= info.maxX() && maxZEx <= info.maxY();
    }

    private boolean isAreaUnlocked(UUID ownerId, GridPosition origin, Vector3Int size) {
        if (origin == null || size == null) return false;
        PlotAreaInfo info = getPlotAreaInfo(ownerId);

        int sx = Math.max(1, size.x());
        int sz = Math.max(1, size.z());

        for (int dx = 0; dx < sx; dx++) {
            for (int dz = 0; dz < sz; dz++) {
                int gx = origin.x() + dx;
                int gz = origin.z() + dz;
                if (!isCellWithinMax(ownerId, gx, gz)) return false;
                if (!isCellUnlocked(ownerId, gx, gz)) return false;
            }
        }
        return true;
    }

    public boolean increasePlotUnlockedArea(UUID ownerId) {
        touchPlayer(ownerId);

        PlayerProfile p = getCachedProfile(ownerId);
        if (p == null || !p.isAdmin()) return false;

        PlotAreaInfo info = getPlotAreaInfo(ownerId);
        PlotUnlockState cur = plotUnlockCache.getOrDefault(ownerId, PlotUnlockState.zero());

        int newExtraX = cur.extraX() + info.increaseX();
        int newExtraY = cur.extraY() + info.increaseY();

        int unlockedX = Math.min(info.maxX(), info.startingX() + newExtraX);
        int unlockedY = Math.min(info.maxY(), info.startingY() + newExtraY);

        PlotUnlockState next = new PlotUnlockState(
                Math.max(0, unlockedX - info.startingX()),
                Math.max(0, unlockedY - info.startingY())
        );

        if (!repository.updatePlotUnlockState(ownerId, next)) return false;
        plotUnlockCache.put(ownerId, next);
        return true;
    }

    public boolean decreasePlotUnlockedArea(UUID ownerId) {
        touchPlayer(ownerId);

        PlayerProfile p = getCachedProfile(ownerId);
        if (p == null || !p.isAdmin()) return false;

        PlotAreaInfo info = getPlotAreaInfo(ownerId);
        PlotUnlockState cur = plotUnlockCache.getOrDefault(ownerId, PlotUnlockState.zero());

        PlotUnlockState next = new PlotUnlockState(
                Math.max(0, cur.extraX() - info.increaseX()),
                Math.max(0, cur.extraY() - info.increaseY())
        );

        // shrink-safe: niente macchine in celle che verrebbero bloccate
        PlotAreaInfo nextInfo = new PlotAreaInfo(
                info.startingX(), info.startingY(),
                info.maxX(), info.maxY(),
                info.increaseX(), info.increaseY(),
                next.extraX(), next.extraY()
        );

        Map<GridPosition, PlacedMachine> grid = playerGrids.get(ownerId);
        if (grid != null && !grid.isEmpty()) {
            for (GridPosition cell : grid.keySet()) {
                if (cell == null) continue;
                int gx = cell.x();
                int gz = cell.z();
                boolean inside = gx >= nextInfo.minX() && gx < nextInfo.maxXExclusive()
                        && gz >= nextInfo.minZ() && gz < nextInfo.maxZExclusive();
                if (!inside) return false;
            }
        }

        if (!repository.updatePlotUnlockState(ownerId, next)) return false;
        plotUnlockCache.put(ownerId, next);
        return true;
    }


    // ==========================================================
    // CONFIG: MinutesToInactive
    // ==========================================================
    public void reloadMinutesToInactive() {
        if (repository instanceof MariaDBAdapter a) {
            this.minutesToInactive = Math.max(1, a.loadMinutesToInactive());
        } else {
            this.minutesToInactive = 5;
        }
    }

    // ==========================================================
    // ACTIVITY / SLEEPING
    // ==========================================================
    public void touchPlayer(UUID ownerId) {
        if (ownerId == null) return;

        lastActivityMs.put(ownerId, System.currentTimeMillis());

        if (sleepingPlayers.remove(ownerId)) {
            Map<GridPosition, PlacedMachine> grid = playerGrids.get(ownerId);
            if (grid != null && !grid.isEmpty()) {
                synchronized (tickingMachines) {
                    for (PlacedMachine pm : new HashSet<>(grid.values())) {
                        if (pm == null) continue;
                        if (!tickingMachines.contains(pm)) tickingMachines.add(pm);
                    }
                }
            }
        }
    }

    public boolean isSleeping(UUID ownerId) {
        return ownerId != null && sleepingPlayers.contains(ownerId);
    }

    private void sweepInactivePlayers(long currentTick) {
        if (currentTick - lastSweepTick < 100) return;
        lastSweepTick = currentTick;

        long now = System.currentTimeMillis();
        long threshold = minutesToInactive * 60_000L;

        for (UUID ownerId : playerGrids.keySet()) {
            long last = lastActivityMs.getOrDefault(ownerId, 0L);
            if (last == 0L) continue;

            if (!sleepingPlayers.contains(ownerId) && (now - last) >= threshold) {
                sleepingPlayers.add(ownerId);
                synchronized (tickingMachines) {
                    tickingMachines.removeIf(m -> m != null && ownerId.equals(m.getOwnerId()));
                }
                if (repository instanceof MariaDBAdapter a) {
                    a.closePlayerSession(ownerId);
                }
            }
        }
    }

    // ==========================================================
    // PROFILES / MONEY
    // ==========================================================
    public PlayerProfile getCachedProfile(UUID uuid) {
        if (uuid == null) return null;
        return activeProfileCache.computeIfAbsent(uuid, repository::loadPlayerProfile);
    }

    public void addMoney(UUID playerId, double amount, String actionType, String itemId) {
        PlayerProfile p = getCachedProfile(playerId);
        if (p == null) return;
        p.modifyMoney(amount);
        repository.savePlayerProfile(p);
        repository.logTransaction(p, actionType, "MONEY", amount, itemId);
        activeProfileCache.put(playerId, p);
    }

    // ==========================================================
    // SHOP / GUI API
    // ==========================================================
    public boolean buyItem(UUID playerId, String itemId, int amount) {
        touchPlayer(playerId);

        PlayerProfile p = getCachedProfile(playerId);
        if (p == null) return false;

        // ✅ Nexus non comprabile (il player ne ha uno solo via inventario/reset)
        if (!p.isAdmin() && "nexus_core".equals(itemId)) return false;

        if (!techManager.canBuyItem(p, itemId)) return false;
        if (!p.isAdmin() && !checkItemCap(playerId, itemId, amount)) return false;

        double cost = blockRegistry.getStats(itemId).basePrice() * amount;
        if (!p.isAdmin() && p.getMoney() < cost) return false;

        if (!p.isAdmin()) addMoney(playerId, -cost, "ITEM_BUY", itemId);
        repository.modifyInventoryItem(playerId, itemId, amount);
        return true;
    }


    public boolean attemptBailout(UUID ownerId) {
        touchPlayer(ownerId);

        PlayerProfile p = getCachedProfile(ownerId);
        if (p == null) return false;

        this.serverConfig = repository.loadServerConfig();
        if (p.getMoney() < serverConfig.sosThreshold()) {
            double diff = serverConfig.sosThreshold() - p.getMoney();
            addMoney(ownerId, diff, "SOS_USE", "bailout");
            return true;
        }
        return false;
    }

    public void resetUserPlot(UUID ownerId) {
        if (ownerId == null) return;
        touchPlayer(ownerId);

        this.serverConfig = repository.loadServerConfig();
        final double startMoney = serverConfig.startMoney();

        ioExecutor.submit(() -> {
            saveAndUnloadSpecific(ownerId);

            // plot reset (NON deve toccare unlocked_extra_x/y)
            repository.clearPlotData(ownerId);

            // ✅ reset money + reset tech tree (con root hidden)
            try {
                PlayerProfile p = repository.loadPlayerProfile(ownerId);
                if (p != null) {
                    p.setMoney(startMoney);
                    p.resetTechTreeToDefaults(); // <-- IMPORTANTISSIMO
                    repository.savePlayerProfile(p);
                    activeProfileCache.put(ownerId, p);
                }
            } catch (Throwable t) {
                t.printStackTrace();
            }

            ensureInventoryAtLeast(ownerId, "conveyor_belt", 10);
            ensureInventoryAtLeast(ownerId, "drill_mk1", 1);
            ensureInventoryAtLeast(ownerId, "nexus_core", 1);

            loadPlotSynchronously(ownerId);
        });
    }

    public void prestigeUser(UUID ownerId) {
        if (ownerId == null) return;
        touchPlayer(ownerId);

        // ricarico config per prendere startMoney + parametri prestige
        this.serverConfig = repository.loadServerConfig();
        final double startMoney = serverConfig.startMoney();
        final int addVoidCoins = Math.max(0, serverConfig.prestigeVoidCoinsAdd());
        final int plotBonus = Math.max(0, serverConfig.prestigePlotBonus());

        ioExecutor.submit(() -> {
            saveAndUnloadSpecific(ownerId);

            // 1) reset plot (NON deve toccare unlocked_extra_x/y)
            repository.clearPlotData(ownerId);

            // 3) incremento dimensione plot (unlocked_extra_x/y) PRIMA del reload (così rigeneri vene su nuova area)
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

            // 2) +void coins, +prestige level, reset money + reset tech tree
            try {
                PlayerProfile p = repository.loadPlayerProfile(ownerId);
                if (p != null) {
                    p.setPrestigeLevel(p.getPrestigeLevel() + 1);
                    if (addVoidCoins > 0) p.modifyVoidCoins(addVoidCoins);

                    p.setMoney(startMoney);
                    p.resetTechTreeToDefaults(); // <-- IMPORTANTISSIMO (reset completo tech)
                    repository.savePlayerProfile(p);
                    activeProfileCache.put(ownerId, p);
                }
            } catch (Throwable t) {
                t.printStackTrace();
            }

            // starter inventory (come reset)
            ensureInventoryAtLeast(ownerId, "conveyor_belt", 10);
            ensureInventoryAtLeast(ownerId, "drill_mk1", 1);
            ensureInventoryAtLeast(ownerId, "nexus_core", 1);

            loadPlotSynchronously(ownerId);
        });
    }




    private void ensureInventoryAtLeast(UUID ownerId, String itemId, int target) {
        if (ownerId == null || itemId == null || target <= 0) return;

        int cur;
        try {
            cur = repository.getInventoryItemCount(ownerId, itemId);
        } catch (Throwable t) {
            cur = 0;
        }

        if (cur < target) repository.modifyInventoryItem(ownerId, itemId, target - cur);
    }






    public PlayerProfile createNewPlayer(String username) {
        this.serverConfig = repository.loadServerConfig();

        UUID newUuid = UUID.randomUUID();
        PlayerProfile p = new PlayerProfile(newUuid);
        p.setUsername(username);
        p.setMoney(serverConfig.startMoney());
        p.setRank(PlayerProfile.PlayerRank.PLAYER);

        repository.savePlayerProfile(p);
        activeProfileCache.put(newUuid, p);

        repository.modifyInventoryItem(newUuid, "drill_mk1", 1);
        repository.modifyInventoryItem(newUuid, "nexus_core", 1);
        repository.modifyInventoryItem(newUuid, "conveyor_belt", 10);

        if (repository instanceof MariaDBAdapter db) {
            db.createPlot(newUuid, 1, 0, 0);
        }

        preloadPlotFromDB(newUuid);
        return p;
    }

    public void deletePlayer(UUID uuid) {
        if (uuid == null) return;

        if (repository instanceof MariaDBAdapter a) a.closePlayerSession(uuid);

        synchronized (tickingMachines) {
            saveAndUnloadSpecific(uuid);
            activeProfileCache.remove(uuid);
        }
        repository.deletePlayerFull(uuid);
    }

    // ==========================================================
    // LOADING PLOTS
    // ==========================================================
    public void preloadPlotFromDB(UUID ownerId) {
        if (ownerId == null) return;

        if (playerGrids.containsKey(ownerId)) {
            touchPlayer(ownerId);
            return;
        }

        touchPlayer(ownerId);

        ioExecutor.submit(() -> {
            loadPlotSynchronously(ownerId);
            PlayerProfile p = repository.loadPlayerProfile(ownerId);
            if (p != null) activeProfileCache.put(ownerId, p);
        });
    }

    public void loadPlotFromDB(UUID ownerId) {
        if (ownerId == null) return;

        if (playerGrids.containsKey(ownerId)) {
            touchPlayer(ownerId);
            return;
        }

        touchPlayer(ownerId);

        ioExecutor.submit(() -> {
            loadPlotSynchronously(ownerId);
            PlayerProfile p = repository.loadPlayerProfile(ownerId);
            if (p != null) activeProfileCache.put(ownerId, p);
        });
    }

    private void loadPlotSynchronously(UUID ownerId) {
        playerGrids.remove(ownerId);
        playerResources.remove(ownerId);
        plotUnlockCache.remove(ownerId);
        sleepingPlayers.remove(ownerId);

        synchronized (tickingMachines) {
            tickingMachines.removeIf(m -> m != null && ownerId.equals(m.getOwnerId()));
        }

        try {
            // load plot unlock state (safe default)
            try {
                plotUnlockCache.put(ownerId, repository.loadPlotUnlockState(ownerId));
            } catch (Throwable t) {
                plotUnlockCache.put(ownerId, PlotUnlockState.zero());
            }

            if (repository instanceof MariaDBAdapter db) {
                Long pid = db.getPlotId(ownerId);
                if (pid != null) {
                    Map<GridPosition, MatterColor> res = db.loadResources(pid);
                    if (res.isEmpty()) generateDefaultResources(ownerId, db, pid, res);
                    playerResources.put(ownerId, res);
                }
            }

            List<PlotObject> dtos = repository.loadPlotMachines(ownerId);
            for (PlotObject d : dtos) {
                PlacedMachine m = MachineFactory.createFromModel(d, ownerId);
                if (m == null) continue;

                // FIX: senza questo, dopo reload/switch tutto resta fermo
                m.setGridContext(this);

                internalAddMachine(ownerId, m);
            }

            touchPlayer(ownerId);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ==========================================================
    // SIMULATION LOOP
    // ==========================================================
    public void tick(long t) {
        sweepInactivePlayers(t);

        synchronized (tickingMachines) {
            for (PlacedMachine m : tickingMachines) {
                if (m == null) continue;
                m.tick(t);
            }
        }
    }

    // ==========================================================
    // PLACEMENT / REMOVAL
    // ==========================================================
    public boolean placeStructure(UUID ownerId, GridPosition pos, String nativeBlockId) {
        touchPlayer(ownerId);

        // ✅ BLOCCO se cap raggiunto
        if (!canPlaceAnotherItem(ownerId)) return false;

        // ✅ bounds + unlock (DOMINIO, non GUI)
        if (!isWithinPlotBounds(ownerId, pos, Vector3Int.one())) return false;
        if (!isAreaUnlocked(ownerId, pos, Vector3Int.one())) return false;

        if (!isAreaClear(ownerId, pos, Vector3Int.one())) return false;

        StructuralBlock block = new StructuralBlock(ownerId, pos, nativeBlockId);
        block.setGridContext(this);

        internalAddMachine(ownerId, block);
        block.onPlace(worldAdapter);

        Long id = repository.createMachine(ownerId, block);
        if (id != null) block.setDbId(id);

        return true;
    }


    public boolean placeMachine(UUID ownerId, GridPosition pos, String typeId, Direction orientation) {
        touchPlayer(ownerId);

        // âœ… BLOCCO se cap raggiunto (prima di consumare inventario!)
        if (!canPlaceAnotherItem(ownerId)) return false;

        PlayerProfile p = getCachedProfile(ownerId);
        if (p == null || !techManager.canBuyItem(p, typeId)) return false;

        Vector3Int dim = blockRegistry.getDimensions(typeId);
        Vector3Int effDim = (orientation == Direction.EAST || orientation == Direction.WEST)
                ? new Vector3Int(dim.z(), dim.y(), dim.x())
                : dim;

        // ✅ bounds + unlock (DOMINIO, non GUI)
        if (!isWithinPlotBounds(ownerId, pos, effDim)) return false;
        if (!isAreaUnlocked(ownerId, pos, effDim)) return false;

        if (!isAreaClear(ownerId, pos, effDim)) return false;

        // consuma inventario solo DOPO il cap check
        if (!p.isAdmin()) {
            if (repository.getInventoryItemCount(ownerId, typeId) <= 0) return false;
            repository.modifyInventoryItem(ownerId, typeId, -1);
        }

        PlotObject dto = new PlotObject(null, null, pos.x(), pos.y(), pos.z(), typeId, null);
        PlacedMachine m = MachineFactory.createFromModel(dto, ownerId);
        if (m == null) {
            // rollback inventario se createFromModel fallisce
            if (!p.isAdmin()) repository.modifyInventoryItem(ownerId, typeId, +1);
            return false;
        }

        m.setOrientation(orientation);
        m.setGridContext(this);

        // âœ… REGOLA DOMINIO: la trivella si piazza SOLO su una vena
        if (m instanceof DrillMachine drill) {
            Map<GridPosition, MatterColor> resMap = playerResources.get(ownerId);
            MatterColor resAt = (resMap != null) ? resMap.get(pos) : null;

            if (resAt == null) {
                // niente vena sotto -> annulla piazzamento + rollback inventario
                if (!p.isAdmin()) repository.modifyInventoryItem(ownerId, typeId, +1);
                return false;
            }

            // vena presente -> trivella minerÃ  quella risorsa
            drill.setResourceToMine(resAt);
        }

        internalAddMachine(ownerId, m);
        m.onPlace(worldAdapter);

        Long id = repository.createMachine(ownerId, m);
        if (id != null) m.setDbId(id);

        repository.logTransaction(p, "PLACE_MACHINE", "NONE", 0, typeId);
        return true;
    }




    public void removeComponent(UUID ownerId, GridPosition pos) {
        touchPlayer(ownerId);

        if (ownerId == null || pos == null) return;

        PlayerProfile p = getCachedProfile(ownerId);
        if (p == null) return;

        // ✅ niente interazioni su celle bloccate (tranne admin)
        if (!p.isAdmin() && !isCellUnlocked(ownerId, pos.x(), pos.z())) return;

        PlacedMachine target = getMachineAt(ownerId, pos);
        if (target == null) return;

        if (!(target instanceof StructuralBlock)) {
            if (!p.isAdmin()) {
                repository.modifyInventoryItem(ownerId, target.getTypeId(), +1);
            }
        }

        internalRemoveMachine(ownerId, target);
        target.onRemove(worldAdapter);

        if (target.getDbId() != null) {
            repository.deleteMachine(target.getDbId());
        }

        repository.logTransaction(p, "REMOVE_MACHINE", "NONE", 0, target.getTypeId());
    }


    // ==========================================================
    // SAVE / UNLOAD
    // ==========================================================
    public void saveAndUnloadSpecific(UUID ownerId) {
        if (ownerId == null) return;

        Map<GridPosition, PlacedMachine> snap = getSnapshot(ownerId);
        if (!snap.isEmpty()) {
            List<PlacedMachine> dirty = snap.values().stream()
                    .filter(Objects::nonNull)
                    .distinct()
                    .filter(PlacedMachine::isDirty)
                    .collect(Collectors.toList());

            if (!dirty.isEmpty()) {
                repository.updateMachinesMetadata(dirty);
                dirty.forEach(PlacedMachine::cleanDirty);
            }
        }

        playerGrids.remove(ownerId);
        playerResources.remove(ownerId);
        plotUnlockCache.remove(ownerId);
        activeProfileCache.remove(ownerId);
        lastActivityMs.remove(ownerId);
        sleepingPlayers.remove(ownerId);

        synchronized (tickingMachines) {
            tickingMachines.removeIf(m -> m != null && ownerId.equals(m.getOwnerId()));
        }
    }

    // ==========================================================
    // GRID INTERNALS
    // ==========================================================
    public PlacedMachine getMachineAt(UUID ownerId, GridPosition pos) {
        if (ownerId == null || pos == null) return null;
        Map<GridPosition, PlacedMachine> g = playerGrids.get(ownerId);
        if (g == null) return null;
        return g.get(pos);
    }

    private void internalAddMachine(UUID ownerId, PlacedMachine m) {
        if (ownerId == null || m == null) return;

        m.setGridContext(this);

        Map<GridPosition, PlacedMachine> grid = playerGrids.computeIfAbsent(ownerId, _k -> new ConcurrentHashMap<>());

        Vector3Int dim;
        try {
            dim = blockRegistry.getDimensions(m.getTypeId());
        } catch (Exception ex) {
            dim = Vector3Int.one();
        }

        Vector3Int effDim = dim;
        if (m.getOrientation() == Direction.EAST || m.getOrientation() == Direction.WEST) {
            effDim = new Vector3Int(dim.z(), dim.y(), dim.x());
        }

        for (int x = 0; x < effDim.x(); x++) {
            for (int y = 0; y < effDim.y(); y++) {
                for (int z = 0; z < effDim.z(); z++) {
                    grid.put(new GridPosition(m.getPos().x() + x, m.getPos().y() + y, m.getPos().z() + z), m);
                }
            }
        }

        if (!sleepingPlayers.contains(ownerId)) {
            synchronized (tickingMachines) {
                if (!tickingMachines.contains(m)) tickingMachines.add(m);
            }
        }
    }

    private void internalRemoveMachine(UUID ownerId, PlacedMachine m) {
        if (ownerId == null || m == null) return;

        Map<GridPosition, PlacedMachine> grid = playerGrids.get(ownerId);
        if (grid != null) {
            grid.entrySet().removeIf(e -> e.getValue() == m);
        }

        synchronized (tickingMachines) {
            tickingMachines.removeIf(x -> x == m);
        }
    }

    private boolean isAreaClear(UUID ownerId, GridPosition pos, Vector3Int size) {
        Map<GridPosition, PlacedMachine> grid = playerGrids.getOrDefault(ownerId, Collections.emptyMap());
        for (int x = 0; x < size.x(); x++) {
            for (int y = 0; y < size.y(); y++) {
                for (int z = 0; z < size.z(); z++) {
                    if (grid.containsKey(new GridPosition(pos.x() + x, pos.y() + y, pos.z() + z))) return false;
                }
            }
        }
        return true;
    }


    // ==========================================================
    // RESOURCES GENERATION
    // ==========================================================
    private void generateDefaultResources(UUID ownerId, MariaDBAdapter db, Long pid, Map<GridPosition, MatterColor> out) {
        this.serverConfig = repository.loadServerConfig();

        PlotAreaInfo info = getPlotAreaInfo(ownerId);

        int maxX = info.maxX();
        int maxY = info.maxY();
        int y = 0;

        // target totali da config (almeno 1)
        int targetRaw = Math.max(serverConfig.veinRaw(), 1);
        int targetRed = Math.max(serverConfig.veinRed(), 1);
        int targetBlue = Math.max(serverConfig.veinBlue(), 1);
        int targetYellow = Math.max(serverConfig.veinYellow(), 1);

        // ✅ dentro unlocked: max 2 raw + 1 per colore
        int unlockedRaw = Math.min(2, targetRaw);
        int unlockedRed = Math.min(1, targetRed);
        int unlockedBlue = Math.min(1, targetBlue);
        int unlockedYellow = Math.min(1, targetYellow);

        // 1) piazza prima quelle “visibili subito” nell’area unlocked
        spawnInUnlockedUntil(db, pid, out, MatterColor.RAW, unlockedRaw, y, info);
        spawnInUnlockedUntil(db, pid, out, MatterColor.RED, unlockedRed, y, info);
        spawnInUnlockedUntil(db, pid, out, MatterColor.BLUE, unlockedBlue, y, info);
        spawnInUnlockedUntil(db, pid, out, MatterColor.YELLOW, unlockedYellow, y, info);

        // 2) tutte le altre vene (se config ne richiede di più) -> SOLO in area locked
        for (int i = countColor(out, MatterColor.RAW); i < targetRaw; i++) {
            spawnVeinInLockedArea(db, pid, out, MatterColor.RAW, y, info, maxX, maxY);
        }
        for (int i = countColor(out, MatterColor.RED); i < targetRed; i++) {
            spawnVeinInLockedArea(db, pid, out, MatterColor.RED, y, info, maxX, maxY);
        }
        for (int i = countColor(out, MatterColor.BLUE); i < targetBlue; i++) {
            spawnVeinInLockedArea(db, pid, out, MatterColor.BLUE, y, info, maxX, maxY);
        }
        for (int i = countColor(out, MatterColor.YELLOW); i < targetYellow; i++) {
            spawnVeinInLockedArea(db, pid, out, MatterColor.YELLOW, y, info, maxX, maxY);
        }
    }

    private void spawnInUnlockedUntil(MariaDBAdapter db, Long pid, Map<GridPosition, MatterColor> out,
                                      MatterColor t, int desiredUnlocked, int y, PlotAreaInfo info) {
        if (desiredUnlocked <= 0) return;

        int minX = info.minX();
        int maxXEx = info.maxXExclusive();
        int minZ = info.minZ();
        int maxZEx = info.maxZExclusive();

        while (countColorInUnlocked(out, t, y, info) < desiredUnlocked) {
            int before = out.size();
            spawnVeinInBounds(db, pid, out, t, y, minX, maxXEx, minZ, maxZEx);

            // se non riesce a piazzare (area piena), evitiamo loop infinito
            if (out.size() == before) break;
        }
    }

    private int countColorInUnlocked(Map<GridPosition, MatterColor> out, MatterColor c, int y, PlotAreaInfo info) {
        if (out == null || out.isEmpty() || c == null) return 0;

        int minX = info.minX();
        int maxXEx = info.maxXExclusive();
        int minZ = info.minZ();
        int maxZEx = info.maxZExclusive();

        int n = 0;
        for (var e : out.entrySet()) {
            GridPosition p = e.getKey();
            MatterColor t = e.getValue();
            if (p == null || t == null) continue;
            if (p.y() != y) continue;
            if (t != c) continue;

            if (p.x() >= minX && p.x() < maxXEx && p.z() >= minZ && p.z() < maxZEx) n++;
        }
        return n;
    }

    private void spawnVeinInLockedArea(MariaDBAdapter db, Long pid, Map<GridPosition, MatterColor> out,
                                       MatterColor t, int y, PlotAreaInfo info, int maxX, int maxY) {
        if (out == null) return;

        int uMinX = info.minX();
        int uMaxXEx = info.maxXExclusive();
        int uMinZ = info.minZ();
        int uMaxZEx = info.maxZExclusive();

        boolean hasLockedArea = (uMinX > 0) || (uMinZ > 0) || (uMaxXEx < maxX) || (uMaxZEx < maxY);

        Random r = new Random();

        // tenta random SOLO fuori dall’unlocked
        if (hasLockedArea) {
            for (int attempt = 0; attempt < 800; attempt++) {
                int x = r.nextInt(maxX);
                int z = r.nextInt(maxY);

                // scarta coordinate dentro area sbloccata
                if (x >= uMinX && x < uMaxXEx && z >= uMinZ && z < uMaxZEx) continue;

                GridPosition key = new GridPosition(x, y, z);
                if (out.containsKey(key)) continue;

                db.saveResource(pid, x, z, t);
                out.put(key, t);
                return;
            }
        }

        // fallback: se non esiste area locked (o è troppo piccola) piazza ovunque nel max plot
        spawnVeinInBounds(db, pid, out, t, y, 0, maxX, 0, maxY);
    }



    private int countColor(Map<GridPosition, MatterColor> out, MatterColor c) {
        if (out == null || out.isEmpty() || c == null) return 0;
        int n = 0;
        for (MatterColor v : out.values()) if (c == v) n++;
        return n;
    }

    private void ensureAtLeastOneInBounds(MariaDBAdapter db, Long pid, Map<GridPosition, MatterColor> out,
                                          MatterColor t, int y,
                                          int minX, int maxXEx, int minZ, int maxZEx) {
        if (countColor(out, t) > 0) return;
        spawnVeinInBounds(db, pid, out, t, y, minX, maxXEx, minZ, maxZEx);
    }

    private void spawnVeinInBounds(MariaDBAdapter db, Long pid, Map<GridPosition, MatterColor> out,
                                   MatterColor t, int y,
                                   int minX, int maxXEx, int minZ, int maxZEx) {

        if (out == null) return;
        if (minX >= maxXEx || minZ >= maxZEx) return;

        Random r = new Random();

        // tenta un po' di volte, poi degrada a scan
        for (int attempt = 0; attempt < 300; attempt++) {
            int x = r.nextInt(maxXEx - minX) + minX;
            int z = r.nextInt(maxZEx - minZ) + minZ;

            GridPosition key = new GridPosition(x, y, z);
            if (out.containsKey(key)) continue;

            db.saveResource(pid, x, z, t);
            out.put(key, t);
            return;
        }

        for (int x = minX; x < maxXEx; x++) {
            for (int z = minZ; z < maxZEx; z++) {
                GridPosition key = new GridPosition(x, y, z);
                if (out.containsKey(key)) continue;

                db.saveResource(pid, x, z, t);
                out.put(key, t);
                return;
            }
        }
    }

    // ==========================================================
    // CAPS

    // ==========================================================
    private boolean checkItemCap(UUID playerId, String itemId, int incomingAmount) {
        int inInventory = repository.getInventoryItemCount(playerId, itemId);

        Map<GridPosition, PlacedMachine> placed = playerGrids.get(playerId);
        long placedCount = (placed != null)
                ? placed.values().stream().filter(Objects::nonNull).distinct().filter(m -> m.getTypeId().equals(itemId)).count()
                : 0;

        long total = inInventory + placedCount + incomingAmount;

        if (itemId.equals("nexus_core") && total > 1) return false;

        if (itemId.equals("drill_mk1")) {
            Map<GridPosition, MatterColor> veins = playerResources.get(playerId);
            if (total > (veins != null ? veins.size() : 0)) return false;
        }

        return true;
    }

    private boolean canPlaceAnotherItem(UUID ownerId) {
        PlayerProfile p = getCachedProfile(ownerId);
        if (p != null && p.isAdmin()) {
            return true; // âœ… ADMIN bypass
        }

        int cap = repository.getDefaultItemPlacedOnPlotCap();
        int placed = repository.getPlotItemsPlaced(ownerId);

        if (cap <= 0) cap = 1;

        if (placed >= cap) {
            System.out.println("âš ï¸ CAP RAGGIUNTO: plot owner=" + ownerId
                    + " item_placed=" + placed + " cap=" + cap
                    + " -> non puoi piazzare altri item, rimuovi qualcosa dalla fabbrica!");
            return false;
        }
        return true;
    }


}