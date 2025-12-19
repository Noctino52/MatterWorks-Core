package com.matterworks.core.managers;

import com.matterworks.core.common.Direction;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.domain.factory.MachineFactory;
import com.matterworks.core.domain.machines.*;
import com.matterworks.core.domain.matter.MatterColor;
import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.domain.shop.MarketManager;
import com.matterworks.core.infrastructure.MariaDBAdapter;
import com.matterworks.core.infrastructure.ServerConfig;
import com.matterworks.core.model.PlotObject;
import com.matterworks.core.ports.IRepository;
import com.matterworks.core.ports.IWorldAccess;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class GridManager {

    private final IRepository repository;
    private final IWorldAccess worldAdapter;
    private final BlockRegistry blockRegistry;
    private final MarketManager marketManager;
    private final TechManager techManager;

    private final Map<UUID, PlayerProfile> activeProfileCache = new ConcurrentHashMap<>();
    private final Map<UUID, Map<GridPosition, PlacedMachine>> playerGrids = new ConcurrentHashMap<>();
    private final Map<UUID, Map<GridPosition, MatterColor>> playerResources = new ConcurrentHashMap<>();
    private final List<PlacedMachine> tickingMachines = Collections.synchronizedList(new ArrayList<>());

    private final ExecutorService ioExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private ServerConfig serverConfig;

    public GridManager(IRepository repository, IWorldAccess worldAdapter, BlockRegistry registry) {
        this.repository = repository;
        this.worldAdapter = worldAdapter;
        this.blockRegistry = registry;
        this.marketManager = new MarketManager(this, repository);
        this.techManager = new TechManager(repository, (repository instanceof MariaDBAdapter a) ? a.getTechDefinitionDAO() : null);
        this.serverConfig = repository.loadServerConfig();
    }

    public TechManager getTechManager() { return techManager; }
    public MarketManager getMarketManager() { return marketManager; }

    // --- ECONOMY & CACHE SYNC ---

    public void addMoney(UUID playerId, double amount, String actionType, String itemId) {
        PlayerProfile p = getCachedProfile(playerId);
        if (p == null) return;
        p.modifyMoney(amount);
        repository.savePlayerProfile(p);
        repository.logTransaction(p, actionType, "MONEY", amount, itemId);
        activeProfileCache.put(playerId, p);
    }

    public PlayerProfile getCachedProfile(UUID uuid) {
        if (uuid == null) return null;
        return activeProfileCache.computeIfAbsent(uuid, repository::loadPlayerProfile);
    }

    // --- PLAYER LIFECYCLE ---

    public PlayerProfile createNewPlayer(String username) {
        this.serverConfig = repository.loadServerConfig();

        UUID newUuid = UUID.randomUUID();
        PlayerProfile p = new PlayerProfile(newUuid);
        p.setUsername(username);
        p.setMoney(serverConfig.startMoney());
        p.setRank(PlayerProfile.PlayerRank.PLAYER);

        repository.savePlayerProfile(p);
        activeProfileCache.put(newUuid, p);

        // Starter Inventory
        repository.modifyInventoryItem(newUuid, "drill_mk1", 1);
        repository.modifyInventoryItem(newUuid, "nexus_core", 1);
        repository.modifyInventoryItem(newUuid, "conveyor_belt", 10);

        if (repository instanceof MariaDBAdapter db) {
            // FIX: Ora usa db.createPlot che abbiamo aggiunto in MariaDBAdapter
            Long plotId = db.createPlot(newUuid, 1, 0, 0);
            if (plotId != null) {
                // Carichiamo subito il plot, che genererà le risorse se mancanti
                loadPlotSynchronously(newUuid);
            }
        }
        return p;
    }

    // --- FIX: Metodo deletePlayer (Richiesto da MatterWorksGUI) ---
    public void deletePlayer(UUID ownerId) {
        synchronized (tickingMachines) {
            saveAndUnloadSpecific(ownerId);
            activeProfileCache.remove(ownerId);
        }
        repository.deletePlayerFull(ownerId);
    }
    // -------------------------------------------------------------

    public void resetUserPlot(UUID ownerId) {
        ioExecutor.submit(() -> {
            synchronized (tickingMachines) {
                PlayerProfile p = getCachedProfile(ownerId);
                saveAndUnloadSpecific(ownerId);
                repository.clearPlotData(ownerId);
                loadPlotSynchronously(ownerId); // Trigger rigenerazione risorse
                if (p != null) repository.logTransaction(p, "PLOT_RESET", "NONE", 0, "user_reset");
            }
        });
    }

    // --- GAMEPLAY & SIMULATION ---

    // --- FIX: Metodo getSnapshot (Richiesto da FactoryPanel) ---
    public Map<GridPosition, PlacedMachine> getSnapshot(UUID id) {
        Map<GridPosition, PlacedMachine> g = playerGrids.get(id);
        return g != null ? new HashMap<>(g) : Collections.emptyMap();
    }
    // ------------------------------------------------------------

    public boolean buyItem(UUID playerId, String itemId, int amount) {
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

    public void loadPlotFromDB(UUID ownerId) {
        if (ownerId == null || activeProfileCache.containsKey(ownerId) && playerGrids.containsKey(ownerId)) return;
        ioExecutor.submit(() -> {
            loadPlotSynchronously(ownerId);
            PlayerProfile p = repository.loadPlayerProfile(ownerId);
            if (p != null) activeProfileCache.put(ownerId, p);
        });
    }

    private void loadPlotSynchronously(UUID ownerId) {
        playerGrids.remove(ownerId);
        playerResources.remove(ownerId);
        synchronized(tickingMachines) { tickingMachines.removeIf(m -> m.getOwnerId().equals(ownerId)); }

        try {
            if (repository instanceof MariaDBAdapter db) {
                Long pid = db.getPlotId(ownerId);
                if (pid != null) {
                    Map<GridPosition, MatterColor> res = db.loadResources(pid);
                    if (res.isEmpty()) {
                        generateDefaultResources(db, pid, res);
                    }
                    playerResources.put(ownerId, res);
                }
            }

            List<com.matterworks.core.model.PlotObject> dtos = repository.loadPlotMachines(ownerId);
            for (com.matterworks.core.model.PlotObject d : dtos) {
                PlacedMachine m = MachineFactory.createFromModel(d, ownerId);
                if (m != null) internalAddMachine(ownerId, m);
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    public void tick(long t) {
        synchronized (tickingMachines) {
            for (PlacedMachine m : tickingMachines) m.tick(t);
        }
    }

    // --- METODO PLACE STRUCTURE (Richiesto) ---
    public boolean placeStructure(UUID ownerId, GridPosition pos, String nativeBlockId) {
        // Bypass inventario, piazzamento diretto
        if (!isAreaClear(ownerId, pos, Vector3Int.one())) return false;

        StructuralBlock block = new StructuralBlock(ownerId, pos, nativeBlockId);
        block.setGridContext(this);

        internalAddMachine(ownerId, block);
        block.onPlace(worldAdapter);
        repository.createMachine(ownerId, block);
        return true;
    }
    // ------------------------------------------

    public boolean placeMachine(UUID ownerId, GridPosition pos, String typeId, Direction orientation) {
        PlayerProfile p = getCachedProfile(ownerId);
        if (p == null || !techManager.canBuyItem(p, typeId)) return false;

        Vector3Int dim = blockRegistry.getDimensions(typeId);
        Vector3Int effDim = (orientation == Direction.EAST || orientation == Direction.WEST) ? new Vector3Int(dim.z(), dim.y(), dim.x()) : dim;
        if (!isAreaClear(ownerId, pos, effDim)) return false;

        if (!p.isAdmin()) {
            if (repository.getInventoryItemCount(ownerId, typeId) <= 0) return false;
            repository.modifyInventoryItem(ownerId, typeId, -1);
        }

        com.matterworks.core.model.PlotObject dto = new com.matterworks.core.model.PlotObject(null, null, pos.x(), pos.y(), pos.z(), typeId, null);
        PlacedMachine m = MachineFactory.createFromModel(dto, ownerId);
        if (m == null) return false;
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
        repository.createMachine(ownerId, m);
        repository.logTransaction(p, "PLACE_MACHINE", "NONE", 0, typeId);
        return true;
    }

    public void removeComponent(UUID ownerId, GridPosition pos) {
        PlacedMachine target = getMachineAt(ownerId, pos);
        if (target == null) return;
        PlayerProfile p = getCachedProfile(ownerId);

        // Restituisci l'item solo se non è una struttura generica
        if (!target.getTypeId().equals("STRUCTURE_GENERIC")) {
            repository.modifyInventoryItem(ownerId, target.getTypeId(), 1);
        }

        if (target.getDbId() != null) repository.deleteMachine(target.getDbId());

        synchronized (tickingMachines) {
            removeFromGridMap(ownerId, target);
            tickingMachines.remove(target);
        }
        target.onRemove();

        if (p != null && !target.getTypeId().equals("STRUCTURE_GENERIC")) {
            repository.logTransaction(p, "REMOVE_MACHINE", "NONE", 0, target.getTypeId());
        }
    }

    // --- UTILS ---

    public boolean isAreaClear(UUID id, GridPosition o, Vector3Int dim) {
        Map<GridPosition, PlacedMachine> g = playerGrids.get(id);
        if (g == null) return true;
        for(int x = 0; x < dim.x(); x++) for(int y = 0; y < dim.y(); y++) for(int z = 0; z < dim.z(); z++)
            if (g.containsKey(new GridPosition(o.x() + x, o.y() + y, o.z() + z))) return false;
        return true;
    }

    public PlacedMachine getMachineAt(UUID id, GridPosition pos) {
        Map<GridPosition, PlacedMachine> g = playerGrids.get(id);
        return g != null ? g.get(pos) : null;
    }

    private void internalAddMachine(UUID id, PlacedMachine m) {
        m.setGridContext(this);
        Map<GridPosition, PlacedMachine> grid = playerGrids.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
        Vector3Int dim = m.getDimensions(); GridPosition o = m.getPos();
        for(int x=0; x<dim.x(); x++) for(int y=0; y<dim.y(); y++) for(int z=0; z<dim.z(); z++)
            grid.put(new GridPosition(o.x()+x, o.y()+y, o.z()+z), m);

        // Aggiungi ai ticking machines solo se ha logica (le strutture generiche hanno tick vuoto, ma va bene aggiungerle per semplicità o filtrarle)
        synchronized (tickingMachines) { tickingMachines.add(m); }
    }

    private void removeFromGridMap(UUID id, PlacedMachine m) {
        Map<GridPosition, PlacedMachine> grid = playerGrids.get(id);
        if (grid == null) return;
        Vector3Int dim = m.getDimensions(); GridPosition o = m.getPos();
        for(int x=0; x<dim.x(); x++) for(int y=0; y<dim.y(); y++) for(int z=0; z<dim.z(); z++)
            grid.remove(new GridPosition(o.x()+x, o.y()+y, o.z()+z));
    }

    private void saveAndUnloadSpecific(UUID ownerId) {
        Map<GridPosition, PlacedMachine> machines = playerGrids.get(ownerId);
        if (machines != null && !machines.isEmpty()) {
            List<PlacedMachine> dirty = machines.values().stream().filter(PlacedMachine::isDirty).distinct().collect(Collectors.toList());
            if (!dirty.isEmpty()) repository.updateMachinesMetadata(dirty);
        }
        playerGrids.remove(ownerId);
        playerResources.remove(ownerId);
        synchronized(tickingMachines) { tickingMachines.removeIf(m -> m.getOwnerId().equals(ownerId)); }
    }

    private boolean checkItemCap(UUID playerId, String itemId, int incomingAmount) {
        int inInventory = repository.getInventoryItemCount(playerId, itemId);
        Map<GridPosition, PlacedMachine> placed = playerGrids.get(playerId);
        long placedCount = (placed != null) ? placed.values().stream().filter(m -> m.getTypeId().equals(itemId)).distinct().count() : 0;
        long total = inInventory + placedCount + incomingAmount;
        if (itemId.equals("nexus_core") && total > 1) return false;
        if (itemId.equals("drill_mk1")) {
            Map<GridPosition, MatterColor> veins = playerResources.get(playerId);
            if (total > (veins != null ? veins.size() : 0)) return false;
        }
        return true;
    }

    private void generateDefaultResources(MariaDBAdapter db, Long plotId, Map<GridPosition, MatterColor> cache) {
        Random rnd = new Random();
        for (int i = 0; i < serverConfig.veinRaw(); i++) createResource(db, plotId, cache, rnd.nextInt(18)+1, 0, rnd.nextInt(18)+1, MatterColor.RAW);
        for (int i = 0; i < serverConfig.veinRed(); i++) createResource(db, plotId, cache, rnd.nextInt(18)+1, 0, rnd.nextInt(18)+1, MatterColor.RED);
        for (int i = 0; i < serverConfig.veinBlue(); i++) createResource(db, plotId, cache, rnd.nextInt(18)+1, 0, rnd.nextInt(18)+1, MatterColor.BLUE);
        for (int i = 0; i < serverConfig.veinYellow(); i++) createResource(db, plotId, cache, rnd.nextInt(18)+1, 0, rnd.nextInt(18)+1, MatterColor.YELLOW);
    }

    private void createResource(MariaDBAdapter db, Long pid, Map<GridPosition, MatterColor> c, int x, int y, int z, MatterColor t) {
        while (c.containsKey(new GridPosition(x, y, z))) {
            x = new Random().nextInt(18) + 1; z = new Random().nextInt(18) + 1;
        }
        db.saveResource(pid, x, z, t);
        c.put(new GridPosition(x,y,z), t);
    }

    public Map<GridPosition, MatterColor> getTerrainResources(UUID id) { return playerResources.getOrDefault(id, Collections.emptyMap()); }
    public BlockRegistry getBlockRegistry() { return blockRegistry; }

    public Map<GridPosition, PlacedMachine> getAllMachinesSnapshot() {
        Map<GridPosition, PlacedMachine> all = new HashMap<>();
        for (var map : playerGrids.values()) all.putAll(map);
        return all;
    }
}