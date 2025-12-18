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
import com.matterworks.core.model.PlotObject;
import com.matterworks.core.ports.IRepository;
import com.matterworks.core.ports.IWorldAccess;
import com.matterworks.core.database.dao.TechDefinitionDAO;
import com.matterworks.core.database.dao.PlotDAO;

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

    // Cache profili per ridurre hit al DB
    private final Map<UUID, PlayerProfile> activeProfileCache = new ConcurrentHashMap<>();

    private final Map<UUID, Map<GridPosition, PlacedMachine>> playerGrids = new ConcurrentHashMap<>();
    private final Map<UUID, Map<GridPosition, MatterColor>> playerResources = new ConcurrentHashMap<>();

    // Lista macchine attive (Sincronizzata per thread-safety nel tick loop)
    private final List<PlacedMachine> tickingMachines = Collections.synchronizedList(new ArrayList<>());

    private final ExecutorService ioExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public GridManager(IRepository repository, IWorldAccess worldAdapter, BlockRegistry registry) {
        this.repository = repository;
        this.worldAdapter = worldAdapter;
        this.blockRegistry = registry;
        this.marketManager = new MarketManager(repository);

        TechDefinitionDAO techDao = null;
        if (repository instanceof MariaDBAdapter adapter) {
            techDao = adapter.getTechDefinitionDAO();
        }
        this.techManager = new TechManager(repository, techDao);
    }

    public TechManager getTechManager() { return techManager; }
    public MarketManager getMarketManager() { return marketManager; }

    /**
     * Switch Contestuale Atomico.
     * Blocca il tick loop per evitare Race Conditions durante il cambio player.
     */
    public void switchPlayerContext(UUID oldUuid, UUID newUuid) {
        synchronized (tickingMachines) {
            try {
                // 1. Salvataggio Vecchio Player
                if (oldUuid != null) {
                    saveAndUnloadSynchronously(oldUuid);
                    activeProfileCache.remove(oldUuid); // Invalida cache vecchia
                }

                // 2. Caricamento Nuovo Player
                if (newUuid != null) {
                    loadPlotSynchronously(newUuid);
                    // Pre-popola cache
                    PlayerProfile p = repository.loadPlayerProfile(newUuid);
                    if (p != null) activeProfileCache.put(newUuid, p);
                }
            } catch (Exception e) {
                System.err.println("🔥 CRITICAL ERROR DURING SWITCH: " + e.getMessage());
                e.printStackTrace();
            }
        }
        System.out.println("✅ [GridManager] Switch context: " + oldUuid + " -> " + newUuid);
    }

    private void saveAndUnloadSynchronously(UUID ownerId) {
        try {
            Map<GridPosition, PlacedMachine> machines = playerGrids.get(ownerId);
            if (machines != null && !machines.isEmpty()) {
                List<PlacedMachine> dirty = machines.values().stream()
                        .filter(PlacedMachine::isDirty)
                        .collect(Collectors.toList());

                if (!dirty.isEmpty()) {
                    repository.updateMachinesMetadata(dirty);
                }
            }
        } catch (Exception e) {
            System.err.println("⚠️ Errore salvataggio player " + ownerId + ": " + e.getMessage());
        } finally {
            // Pulizia forzata della memoria
            playerGrids.remove(ownerId);
            playerResources.remove(ownerId);
            tickingMachines.removeIf(m -> m.getOwnerId().equals(ownerId));
        }
    }

    private void loadPlotSynchronously(UUID ownerId) {
        // Pulizia preventiva per evitare duplicati
        unloadPlot(ownerId);

        try {
            // 1. Risorse
            if (repository instanceof MariaDBAdapter db) {
                Long pid = db.getPlotId(ownerId);
                if (pid != null) {
                    Map<GridPosition, MatterColor> res = db.loadResources(pid);
                    if (res.isEmpty()) generateDefaultResources(db, pid, res);
                    playerResources.put(ownerId, res);
                }
            }
            // 2. Macchine
            List<PlotObject> dtos = repository.loadPlotMachines(ownerId);
            for (PlotObject d : dtos) {
                PlacedMachine m = MachineFactory.createFromModel(d, ownerId);
                if (m != null) internalAddMachine(ownerId, m);
            }
        } catch (Exception e) {
            System.err.println("⚠️ Errore caricamento player " + ownerId);
            e.printStackTrace();
        }
    }

    public PlayerProfile getCachedProfile(UUID uuid) {
        if (uuid == null) return null;
        return activeProfileCache.computeIfAbsent(uuid, repository::loadPlayerProfile);
    }

    private void updateProfileCache(PlayerProfile p) {
        if (p != null) activeProfileCache.put(p.getPlayerId(), p);
    }

    public PlayerProfile createNewPlayer(String username) {
        UUID newUuid = UUID.randomUUID();
        PlayerProfile p = new PlayerProfile(newUuid);
        p.setUsername(username);
        p.setMoney(1000.0);
        p.setRank(PlayerProfile.PlayerRank.PLAYER);
        repository.savePlayerProfile(p);
        updateProfileCache(p);

        if (repository instanceof MariaDBAdapter adapter) {
            PlotDAO plotDAO = new PlotDAO(adapter.getDbManager());
            Long plotId = plotDAO.createPlot(newUuid, 1, 0, 0);
            if (plotId != null) generateDefaultResources(adapter, plotId, new HashMap<>());
        }
        return p;
    }

    public void deletePlayer(UUID ownerId) {
        synchronized (tickingMachines) {
            saveAndUnloadSynchronously(ownerId);
            activeProfileCache.remove(ownerId);
        }
        repository.deletePlayerFull(ownerId);
        System.out.println("[GridManager] Player " + ownerId + " eliminato.");
    }

    public boolean attemptBailout(UUID ownerId) {
        PlayerProfile player = getCachedProfile(ownerId);
        if (player == null || !(repository instanceof MariaDBAdapter adapter)) return false;

        double threshold = adapter.getSosThreshold();
        if (player.getMoney() < threshold) {
            player.setMoney(threshold);
            repository.savePlayerProfile(player);
            updateProfileCache(player);
            return true;
        }
        return false;
    }

    public void resetUserPlot(UUID ownerId) {
        ioExecutor.submit(() -> {
            synchronized (tickingMachines) {
                saveAndUnloadSynchronously(ownerId);
                if (repository instanceof MariaDBAdapter db) db.clearPlotData(ownerId);
                loadPlotSynchronously(ownerId);
            }
        });
    }

    public boolean buyItem(UUID playerId, String itemId, int amount) {
        PlayerProfile p = getCachedProfile(playerId);
        if (p == null) return false;

        if (!techManager.canBuyItem(p, itemId)) {
            System.err.println("🔒 LOCK: Ricerca necessaria per " + itemId);
            return false;
        }

        double cost = blockRegistry.getStats(itemId).basePrice() * amount;
        if (!p.isAdmin() && p.getMoney() < cost) return false;

        if (!p.isAdmin()) {
            p.modifyMoney(-cost);
            repository.savePlayerProfile(p);
            updateProfileCache(p);
        }
        repository.modifyInventoryItem(playerId, itemId, amount);
        return true;
    }

    public boolean placeMachine(UUID ownerId, GridPosition pos, String typeId, Direction orientation) {
        PlayerProfile player = getCachedProfile(ownerId);
        if (player == null) return false;

        if (!techManager.canBuyItem(player, typeId)) {
            System.err.println("🔒 LOCK: Tech mancante per " + typeId);
            return false;
        }

        if (typeId.equals("drill_mk1")) {
            Map<GridPosition, MatterColor> res = playerResources.get(ownerId);
            if (res == null || !res.containsKey(pos)) return false;
        }

        if (!player.isAdmin() && repository.getInventoryItemCount(ownerId, typeId) <= 0) return false;

        Vector3Int dim = blockRegistry.getDimensions(typeId);
        Vector3Int effDim = (orientation == Direction.EAST || orientation == Direction.WEST) ? new Vector3Int(dim.z(), dim.y(), dim.x()) : dim;

        if (!isAreaClear(ownerId, pos, effDim)) return false;

        if (!player.isAdmin()) repository.modifyInventoryItem(ownerId, typeId, -1);

        PlotObject dto = new PlotObject(null, null, pos.x(), pos.y(), pos.z(), typeId, null);
        PlacedMachine m = MachineFactory.createFromModel(dto, ownerId);
        if (m == null) return false;

        m.setOrientation(orientation);
        m.setGridContext(this);
        if (m instanceof DrillMachine drill) drill.setResourceToMine(playerResources.get(ownerId).get(pos));

        internalAddMachine(ownerId, m);
        m.onPlace(worldAdapter);
        repository.createMachine(ownerId, m);
        return true;
    }

    public void removeComponent(UUID ownerId, GridPosition pos) {
        PlacedMachine target = getMachineAt(ownerId, pos);
        if (target == null) return;
        repository.modifyInventoryItem(ownerId, target.getTypeId(), 1);
        if (target.getDbId() != null) repository.deleteMachine(target.getDbId());

        synchronized (tickingMachines) {
            removeFromGridMap(ownerId, target);
            tickingMachines.remove(target);
        }
        target.onRemove();
    }

    public boolean isAreaClear(UUID id, GridPosition o, Vector3Int dim) {
        Map<GridPosition, PlacedMachine> g = playerGrids.get(id);
        if (g == null) return true;
        for(int x=0; x<dim.x(); x++) for(int y=0; y<dim.y(); y++) for(int z=0; z<dim.z(); z++)
            if(g.containsKey(new GridPosition(o.x()+x, o.y()+y, o.z()+z))) return false;
        return true;
    }

    public PlacedMachine getMachineAt(UUID id, GridPosition pos) {
        Map<GridPosition, PlacedMachine> g = playerGrids.get(id);
        return g != null ? g.get(pos) : null;
    }

    public void tick(long t) {
        List<PlacedMachine> snap;
        synchronized(tickingMachines){ snap = new ArrayList<>(tickingMachines); }
        snap.forEach(m -> m.tick(t));
    }

    private void internalAddMachine(UUID id, PlacedMachine m) {
        m.setGridContext(this);
        Map<GridPosition, PlacedMachine> grid = playerGrids.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
        Vector3Int dim = m.getDimensions(); GridPosition o = m.getPos();
        for(int x=0; x<dim.x(); x++) for(int y=0; y<dim.y(); y++) for(int z=0; z<dim.z(); z++)
            grid.put(new GridPosition(o.x()+x, o.y()+y, o.z()+z), m);

        synchronized (tickingMachines) { tickingMachines.add(m); }
    }

    private void removeFromGridMap(UUID id, PlacedMachine m) {
        Map<GridPosition, PlacedMachine> grid = playerGrids.get(id);
        if (grid == null) return;
        Vector3Int dim = m.getDimensions(); GridPosition o = m.getPos();
        for(int x=0; x<dim.x(); x++) for(int y=0; y<dim.y(); y++) for(int z=0; z<dim.z(); z++)
            grid.remove(new GridPosition(o.x()+x, o.y()+y, o.z()+z));
    }

    // Legacy support via async wrapper
    public void loadPlotFromDB(UUID ownerId) {
        ioExecutor.submit(() -> {
            synchronized (tickingMachines) { loadPlotSynchronously(ownerId); }
        });
    }

    private void unloadPlot(UUID id) {
        playerGrids.remove(id);
        playerResources.remove(id);
        synchronized(tickingMachines) { tickingMachines.removeIf(m -> m.getOwnerId().equals(id)); }
    }

    private void generateDefaultResources(MariaDBAdapter db, Long plotId, Map<GridPosition, MatterColor> cache) {
        Random rnd = new Random();
        for (int i = 0; i < 3; i++) createResource(db, plotId, cache, rnd.nextInt(15)+2, 0, rnd.nextInt(15)+2, MatterColor.RAW);
        createResource(db, plotId, cache, rnd.nextInt(15)+2, 0, rnd.nextInt(15)+2, MatterColor.RED);
        createResource(db, plotId, cache, rnd.nextInt(15)+2, 0, rnd.nextInt(15)+2, MatterColor.BLUE);
    }

    private void createResource(MariaDBAdapter db, Long pid, Map<GridPosition, MatterColor> c, int x, int y, int z, MatterColor t) {
        if(c.containsKey(new GridPosition(x,y,z))) return;
        db.saveResource(pid, x, z, t); c.put(new GridPosition(x,y,z), t);
    }

    public Map<GridPosition, PlacedMachine> getSnapshot(UUID id) {
        Map<GridPosition, PlacedMachine> g = playerGrids.get(id);
        return g != null ? new HashMap<>(g) : Collections.emptyMap();
    }

    public Map<GridPosition, PlacedMachine> getAllMachinesSnapshot() {
        Map<GridPosition, PlacedMachine> all = new HashMap<>();
        for (var map : playerGrids.values()) all.putAll(map);
        return all;
    }

    public Map<GridPosition, MatterColor> getTerrainResources(UUID id) { return playerResources.getOrDefault(id, Collections.emptyMap()); }
    public BlockRegistry getBlockRegistry() { return blockRegistry; }
}