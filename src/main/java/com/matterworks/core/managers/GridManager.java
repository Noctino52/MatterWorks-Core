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

        ioExecutor.submit(() -> {
            saveAndUnloadSpecific(ownerId);
            repository.clearPlotData(ownerId);
            loadPlotSynchronously(ownerId);
        });
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
        sleepingPlayers.remove(ownerId);

        synchronized (tickingMachines) {
            tickingMachines.removeIf(m -> m != null && ownerId.equals(m.getOwnerId()));
        }

        try {
            if (repository instanceof MariaDBAdapter db) {
                Long pid = db.getPlotId(ownerId);
                if (pid != null) {
                    Map<GridPosition, MatterColor> res = db.loadResources(pid);
                    if (res.isEmpty()) generateDefaultResources(db, pid, res);
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

        // ✅ BLOCCO se cap raggiunto (prima di consumare inventario!)
        if (!canPlaceAnotherItem(ownerId)) return false;

        PlayerProfile p = getCachedProfile(ownerId);
        if (p == null || !techManager.canBuyItem(p, typeId)) return false;

        Vector3Int dim = blockRegistry.getDimensions(typeId);
        Vector3Int effDim = (orientation == Direction.EAST || orientation == Direction.WEST)
                ? new Vector3Int(dim.z(), dim.y(), dim.x())
                : dim;

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
            if (p != null && !p.isAdmin()) repository.modifyInventoryItem(ownerId, typeId, +1);
            return false;
        }

        m.setOrientation(orientation);
        m.setGridContext(this);

        if (m instanceof DrillMachine drill) {
            Map<GridPosition, MatterColor> resMap = playerResources.get(ownerId);
            if (resMap != null) {
                MatterColor resAt = resMap.get(pos);
                if (resAt != null) drill.setResourceToMine(resAt);
            }
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

        PlacedMachine target = getMachineAt(ownerId, pos);
        if (target == null) return;

        PlayerProfile p = getCachedProfile(ownerId);

        if (!(target instanceof StructuralBlock)) {
            if (p != null && !p.isAdmin()) {
                repository.modifyInventoryItem(ownerId, target.getTypeId(), +1);
            }
        }

        internalRemoveMachine(ownerId, target);
        target.onRemove(worldAdapter);

        if (target.getDbId() != null) {
            repository.deleteMachine(target.getDbId());
        }

        if (p != null) repository.logTransaction(p, "REMOVE_MACHINE", "NONE", 0, target.getTypeId());
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
    private void generateDefaultResources(MariaDBAdapter db, Long pid, Map<GridPosition, MatterColor> c) {
        this.serverConfig = repository.loadServerConfig();
        int y = 0;

        for (int i = 0; i < serverConfig.veinRaw(); i++) spawnVein(db, pid, c, MatterColor.RAW, y);
        for (int i = 0; i < serverConfig.veinRed(); i++) spawnVein(db, pid, c, MatterColor.RED, y);
        for (int i = 0; i < serverConfig.veinBlue(); i++) spawnVein(db, pid, c, MatterColor.BLUE, y);
        for (int i = 0; i < serverConfig.veinYellow(); i++) spawnVein(db, pid, c, MatterColor.YELLOW, y);
    }

    private void spawnVein(MariaDBAdapter db, Long pid, Map<GridPosition, MatterColor> c, MatterColor t, int y) {
        Random r = new Random();
        int x = r.nextInt(18) + 1;
        int z = r.nextInt(18) + 1;
        while (c.containsKey(new GridPosition(x, y, z))) {
            x = r.nextInt(18) + 1;
            z = r.nextInt(18) + 1;
        }
        db.saveResource(pid, x, z, t);
        c.put(new GridPosition(x, y, z), t);
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
            return true; // ✅ ADMIN bypass
        }

        int cap = repository.getDefaultItemPlacedOnPlotCap();
        int placed = repository.getPlotItemsPlaced(ownerId);

        if (cap <= 0) cap = 1;

        if (placed >= cap) {
            System.out.println("⚠️ CAP RAGGIUNTO: plot owner=" + ownerId
                    + " item_placed=" + placed + " cap=" + cap
                    + " -> non puoi piazzare altri item, rimuovi qualcosa dalla fabbrica!");
            return false;
        }
        return true;
    }


}
