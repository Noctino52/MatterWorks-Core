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

    // Cache Globale: Mantiene TUTTI i player caricati per la simulazione
    private final Map<UUID, PlayerProfile> activeProfileCache = new ConcurrentHashMap<>();
    private final Map<UUID, Map<GridPosition, PlacedMachine>> playerGrids = new ConcurrentHashMap<>();
    private final Map<UUID, Map<GridPosition, MatterColor>> playerResources = new ConcurrentHashMap<>();

    // Lista unica sincronizzata per il tick loop
    private final List<PlacedMachine> tickingMachines = Collections.synchronizedList(new ArrayList<>());

    private final ExecutorService ioExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private ServerConfig serverConfig;

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

        this.serverConfig = repository.loadServerConfig();
        System.out.println("⚙️ [Core] Config Loaded: StartMoney=" + serverConfig.startMoney() + ", Veins=" + serverConfig.veinRaw());
    }

    public TechManager getTechManager() { return techManager; }
    public MarketManager getMarketManager() { return marketManager; }

    // --- CARICAMENTO SIMULAZIONE (No Unload) ---

    /**
     * Metodo richiesto da Main.java.
     * Carica il plot nella simulazione globale se non è già presente.
     */
    public void loadPlotFromDB(UUID ownerId) {
        if (ownerId == null) return;

        // Se è già in memoria, non facciamo nulla (evita IO inutile)
        if (activeProfileCache.containsKey(ownerId)) return;

        ioExecutor.submit(() -> {
            System.out.println("📥 [Sim] Loading chunk for " + ownerId);
            loadPlotSynchronously(ownerId);
            PlayerProfile p = repository.loadPlayerProfile(ownerId);
            if (p != null) activeProfileCache.put(ownerId, p);
        });
    }

    private void loadPlotSynchronously(UUID ownerId) {
        // Rimuove eventuali dati parziali SOLO di questo player
        unloadPlotSpecific(ownerId);

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
            e.printStackTrace();
        }
    }

    private void unloadPlotSpecific(UUID ownerId) {
        playerGrids.remove(ownerId);
        playerResources.remove(ownerId);
        // Rimuove dalla simulazione solo le macchine di questo utente
        synchronized (tickingMachines) {
            tickingMachines.removeIf(m -> m.getOwnerId().equals(ownerId));
        }
    }

    // --- LOGICA DI GIOCO & CAP ---

    public boolean placeMachine(UUID ownerId, GridPosition pos, String typeId, Direction orientation) {
        PlayerProfile player = getCachedProfile(ownerId);
        if (player == null) return false;

        // 1. Tech Check
        if (!techManager.canBuyItem(player, typeId)) {
            System.err.println("🔒 [Game] Tech mancante: " + typeId);
            return false;
        }

        // 2. Vein Check (Drill)
        if (typeId.equals("drill_mk1")) {
            Map<GridPosition, MatterColor> res = playerResources.get(ownerId);
            if (res == null || !res.containsKey(pos)) return false;
        }

        // 3. Inventory Check
        if (!player.isAdmin() && repository.getInventoryItemCount(ownerId, typeId) <= 0) return false;

        // 4. Area Check
        Vector3Int dim = blockRegistry.getDimensions(typeId);
        Vector3Int effDim = (orientation == Direction.EAST || orientation == Direction.WEST) ? new Vector3Int(dim.z(), dim.y(), dim.x()) : dim;
        if (!isAreaClear(ownerId, pos, effDim)) return false;

        // Esegui Piazzamento
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

    public boolean buyItem(UUID playerId, String itemId, int amount) {
        PlayerProfile p = getCachedProfile(playerId);
        if (p == null) return false;

        if (!techManager.canBuyItem(p, itemId)) {
            System.err.println("🔒 [Shop] Tech mancante per " + itemId);
            return false;
        }

        // --- CAP SYSTEM ---
        if (!p.isAdmin() && !checkItemCap(playerId, itemId, amount)) return false;

        double cost = blockRegistry.getStats(itemId).basePrice() * amount;
        if (!p.isAdmin() && p.getMoney() < cost) return false;

        if (!p.isAdmin()) {
            p.modifyMoney(-cost);
            repository.savePlayerProfile(p);
            activeProfileCache.put(playerId, p); // Update Cache
        }
        repository.modifyInventoryItem(playerId, itemId, amount);
        return true;
    }

    private boolean checkItemCap(UUID playerId, String itemId, int incomingAmount) {
        int inInventory = repository.getInventoryItemCount(playerId, itemId);
        Map<GridPosition, PlacedMachine> placed = playerGrids.get(playerId);
        long placedCount = (placed != null) ? placed.values().stream().filter(m -> m.getTypeId().equals(itemId)).count() : 0;
        long total = inInventory + placedCount + incomingAmount;

        // Nexus: Max 1
        if (itemId.equals("nexus_core") && total > 1) {
            System.err.println("🚫 CAP: Massimo 1 Nexus consentito.");
            return false;
        }
        // Trivelle: Max <= Numero Vene
        if (itemId.equals("drill_mk1")) {
            Map<GridPosition, MatterColor> veins = playerResources.get(playerId);
            int totalVeins = (veins != null) ? veins.size() : 0;
            if (total > totalVeins) {
                System.err.println("🚫 CAP: Max trivelle raggiunto (" + totalVeins + ")");
                return false;
            }
        }
        return true;
    }

    // --- PLAYER MANAGEMENT ---

    public PlayerProfile createNewPlayer(String username) {
        this.serverConfig = repository.loadServerConfig(); // Reload config
        UUID newUuid = UUID.randomUUID();
        PlayerProfile p = new PlayerProfile(newUuid);
        p.setUsername(username);
        p.setMoney(serverConfig.startMoney()); // Config Money
        p.setRank(PlayerProfile.PlayerRank.PLAYER);

        repository.savePlayerProfile(p);
        activeProfileCache.put(p.getPlayerId(), p);

        // Starter Kit
        repository.modifyInventoryItem(newUuid, "drill_mk1", 1);
        repository.modifyInventoryItem(newUuid, "nexus_core", 1);
        repository.modifyInventoryItem(newUuid, "conveyor_belt", 1);

        if (repository instanceof MariaDBAdapter adapter) {
            PlotDAO plotDAO = new PlotDAO(adapter.getDbManager());
            Long plotId = plotDAO.createPlot(newUuid, 1, 0, 0);
            if (plotId != null) generateDefaultResources(adapter, plotId, new HashMap<>());
        }
        return p;
    }

    public void deletePlayer(UUID ownerId) {
        unloadPlotSpecific(ownerId);
        activeProfileCache.remove(ownerId);
        repository.deletePlayerFull(ownerId);
        System.out.println("💀 [Manager] Player " + ownerId + " eliminato.");
    }

    public boolean attemptBailout(UUID ownerId) {
        PlayerProfile player = getCachedProfile(ownerId);
        if (player == null) return false;

        this.serverConfig = repository.loadServerConfig();
        if (player.getMoney() < serverConfig.sosThreshold()) {
            player.setMoney(serverConfig.sosThreshold());
            repository.savePlayerProfile(player);
            activeProfileCache.put(ownerId, player);
            return true;
        }
        return false;
    }

    public void resetUserPlot(UUID ownerId) {
        ioExecutor.submit(() -> {
            unloadPlotSpecific(ownerId);
            if (repository instanceof MariaDBAdapter db) db.clearPlotData(ownerId);
            loadPlotSynchronously(ownerId);
        });
    }

    // --- UTILS ---

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
        for(int x = 0; x < dim.x(); x++) {
            for(int y = 0; y < dim.y(); y++) {
                for(int z = 0; z < dim.z(); z++) {
                    GridPosition checkPos = new GridPosition(o.x() + x, o.y() + y, o.z() + z);
                    if (g.containsKey(checkPos)) return false;
                }
            }
        }
        return true;
    }

    public PlacedMachine getMachineAt(UUID id, GridPosition pos) {
        Map<GridPosition, PlacedMachine> g = playerGrids.get(id);
        return g != null ? g.get(pos) : null;
    }

    public void tick(long t) {
        // Itera su TUTTO il mondo caricato (Multitenant Simulation)
        synchronized (tickingMachines) {
            for (PlacedMachine m : tickingMachines) {
                m.tick(t);
            }
        }
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

    private void generateDefaultResources(MariaDBAdapter db, Long plotId, Map<GridPosition, MatterColor> cache) {
        Random rnd = new Random();
        for (int i = 0; i < serverConfig.veinRaw(); i++) createResource(db, plotId, cache, rnd.nextInt(18)+1, 0, rnd.nextInt(18)+1, MatterColor.RAW);
        for (int i = 0; i < serverConfig.veinRed(); i++) createResource(db, plotId, cache, rnd.nextInt(18)+1, 0, rnd.nextInt(18)+1, MatterColor.RED);
        for (int i = 0; i < serverConfig.veinBlue(); i++) createResource(db, plotId, cache, rnd.nextInt(18)+1, 0, rnd.nextInt(18)+1, MatterColor.BLUE);
        for (int i = 0; i < serverConfig.veinYellow(); i++) createResource(db, plotId, cache, rnd.nextInt(18)+1, 0, rnd.nextInt(18)+1, MatterColor.YELLOW);
    }

    private void createResource(MariaDBAdapter db, Long pid, Map<GridPosition, MatterColor> c, int x, int y, int z, MatterColor t) {
        while (c.containsKey(new GridPosition(x, y, z))) {
            x = new Random().nextInt(18) + 1;
            z = new Random().nextInt(18) + 1;
        }
        db.saveResource(pid, x, z, t);
        c.put(new GridPosition(x,y,z), t);
    }

    public PlayerProfile getCachedProfile(UUID uuid) {
        if (uuid == null) return null;
        return activeProfileCache.computeIfAbsent(uuid, repository::loadPlayerProfile);
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