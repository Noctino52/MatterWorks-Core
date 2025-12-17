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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GridManager {

    private final IRepository repository;
    private final IWorldAccess worldAdapter;
    private final BlockRegistry blockRegistry;
    private final MarketManager marketManager;

    private final Map<UUID, Map<GridPosition, PlacedMachine>> playerGrids = new ConcurrentHashMap<>();
    private final Map<UUID, Map<GridPosition, MatterColor>> playerResources = new ConcurrentHashMap<>();
    private final List<PlacedMachine> tickingMachines = Collections.synchronizedList(new ArrayList<>());
    private final ExecutorService ioExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public GridManager(IRepository repository, IWorldAccess worldAdapter, BlockRegistry registry) {
        this.repository = repository;
        this.worldAdapter = worldAdapter;
        this.blockRegistry = registry;
        this.marketManager = new MarketManager(repository);
    }

    private String log(UUID id) {
        PlayerProfile p = repository.loadPlayerProfile(id);
        return "[" + (p != null ? p.getUsername() : "Unknown") + "] ";
    }

    // --- FIX: METODO createNewPlayer ---
    public PlayerProfile createNewPlayer(String username) {
        UUID newUuid = UUID.randomUUID();
        PlayerProfile p = new PlayerProfile(newUuid);
        p.setUsername(username);
        p.setMoney(1000.0);
        p.setRank(PlayerProfile.PlayerRank.PLAYER);

        repository.savePlayerProfile(p);

        if (repository instanceof MariaDBAdapter adapter) {
            Long plotId = new com.matterworks.core.database.dao.PlotDAO(adapter.getDbManager())
                    .createPlot(newUuid, 1, 0, 0);

            if (plotId != null) {
                generateDefaultResources(adapter, plotId, new HashMap<>());
                System.out.println("[System] Initialized plot and veins for: " + username);
            }
        }
        return p;
    }

    // --- FIX: METODO attemptBailout (SOS) ---
    public boolean attemptBailout(UUID ownerId) {
        PlayerProfile player = repository.loadPlayerProfile(ownerId);
        if (player == null || !(repository instanceof MariaDBAdapter adapter)) return false;

        double threshold = adapter.getSosThreshold(); // Recupera da server_gamestate

        // Verifica se il giocatore è effettivamente sotto la soglia di povertà
        if (player.getMoney() < threshold) {
            double refill = threshold - player.getMoney();
            player.setMoney(threshold);
            repository.savePlayerProfile(player);
            System.out.println(log(ownerId) + "🚑 SOS APPROVATO: Saldo ripristinato a $" + threshold);
            return true;
        }
        System.out.println(log(ownerId) + "❌ SOS NEGATO: Saldo già sopra la soglia.");
        return false;
    }

    public boolean buyItem(UUID playerId, String itemId, int amount) {
        var stats = blockRegistry.getStats(itemId);
        double cost = stats.basePrice() * amount;
        PlayerProfile p = repository.loadPlayerProfile(playerId);
        if (p == null) return false;

        if (!p.isAdmin() && p.getMoney() < cost) {
            System.out.println(log(playerId) + "💸 Fondi insufficienti per lo shop.");
            return false;
        }

        if (!p.isAdmin()) {
            p.modifyMoney(-cost);
            repository.savePlayerProfile(p);
        }
        repository.modifyInventoryItem(playerId, itemId, amount);
        System.out.println(log(playerId) + "🛒 Acquisto: " + amount + "x " + itemId);
        return true;
    }

    public void resetUserPlot(UUID ownerId) {
        ioExecutor.submit(() -> {
            System.out.println(log(ownerId) + "⚠️ Reset totale del plot richiesto.");
            unloadPlot(ownerId);
            if (repository instanceof MariaDBAdapter db) db.clearPlotData(ownerId);
            loadPlotFromDB(ownerId);
        });
    }

    public boolean placeMachine(UUID ownerId, GridPosition pos, String typeId, Direction orientation) {
        PlayerProfile player = repository.loadPlayerProfile(ownerId);
        if (player == null) return false;

        // Requisito: Trivelle solo su vene [cite: 673-674]
        if (typeId.equals("drill_mk1")) {
            Map<GridPosition, MatterColor> res = playerResources.get(ownerId);
            if (res == null || !res.containsKey(pos)) {
                System.out.println(log(ownerId) + "🚫 Fallito: Trivella piazzata fuori vena a " + pos);
                return false;
            }
        }

        boolean isAdmin = player.isAdmin();
        if (!isAdmin && repository.getInventoryItemCount(ownerId, typeId) <= 0) {
            System.out.println(log(ownerId) + "🎒 Fallito: Inventario vuoto.");
            return false;
        }

        var stats = blockRegistry.getStats(typeId);
        Vector3Int dim = stats.dimensions();
        Vector3Int effDim = (orientation == Direction.EAST || orientation == Direction.WEST) ? new Vector3Int(dim.z(), dim.y(), dim.x()) : dim;

        if (!isAreaClear(ownerId, pos, effDim)) return false;

        if (!isAdmin) repository.modifyInventoryItem(ownerId, typeId, -1);

        PlotObject dto = new PlotObject(null, null, pos.x(), pos.y(), pos.z(), typeId, null);
        PlacedMachine m = MachineFactory.createFromModel(dto, ownerId);
        if (m == null) return false;

        m.setOrientation(orientation);
        if (m instanceof DrillMachine drill) drill.setResourceToMine(playerResources.get(ownerId).get(pos));

        internalAddMachine(ownerId, m);
        m.onPlace(worldAdapter);
        repository.createMachine(ownerId, m);
        System.out.println(log(ownerId) + "✅ Piazzato " + typeId + " a " + pos);
        return true;
    }

    // --- METODI DI SUPPORTO (Snapshots, Market, Inventory) ---
    public MarketManager getMarketManager() { return marketManager; }
    public Map<GridPosition, PlacedMachine> getAllMachinesSnapshot() {
        Map<GridPosition, PlacedMachine> all = new HashMap<>();
        for (var map : playerGrids.values()) all.putAll(map);
        return all;
    }

    // ... (loadPlotFromDB, internalAddMachine, generateDefaultResources rimangono identici)
    public void loadPlotFromDB(UUID ownerId) {
        ioExecutor.submit(() -> {
            try {
                unloadPlot(ownerId);
                if (repository instanceof MariaDBAdapter db) {
                    Long pid = db.getPlotId(ownerId);
                    if (pid != null) {
                        Map<GridPosition, MatterColor> res = db.loadResources(pid);
                        if (res.isEmpty()) generateDefaultResources(db, pid, res);
                        playerResources.put(ownerId, res);
                    }
                }
                List<PlotObject> dtos = repository.loadPlotMachines(ownerId);
                for(PlotObject d : dtos) {
                    PlacedMachine m = MachineFactory.createFromModel(d, ownerId);
                    if(m!=null) internalAddMachine(ownerId, m);
                }
            } catch(Exception e) { e.printStackTrace(); }
        });
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

    private void unloadPlot(UUID id) {
        playerGrids.remove(id); playerResources.remove(id);
        synchronized(tickingMachines) { tickingMachines.removeIf(m -> m.getOwnerId().equals(id)); }
    }

    private void internalAddMachine(UUID id, PlacedMachine m) {
        m.setGridContext(this);
        Map<GridPosition, PlacedMachine> grid = playerGrids.computeIfAbsent(id, k->new ConcurrentHashMap<>());
        Vector3Int dim = m.getDimensions(); GridPosition o = m.getPos();
        for(int x=0;x<dim.x();x++) for(int y=0;y<dim.y();y++) for(int z=0;z<dim.z();z++)
            grid.put(new GridPosition(o.x()+x, o.y()+y, o.z()+z), m);
        tickingMachines.add(m);
    }

    public void removeComponent(UUID ownerId, GridPosition pos) {
        PlacedMachine target = getMachineAt(ownerId, pos);
        if (target == null) return;
        repository.modifyInventoryItem(ownerId, target.getTypeId(), 1);
        if (target.getDbId() != null) repository.deleteMachine(target.getDbId());
        removeFromGridMap(ownerId, target);
        synchronized (tickingMachines) { tickingMachines.remove(target); }
        target.onRemove();
    }

    private void removeFromGridMap(UUID id, PlacedMachine m) {
        Map<GridPosition, PlacedMachine> grid = playerGrids.get(id);
        if (grid == null) return;
        Vector3Int dim = m.getDimensions(); GridPosition o = m.getPos();
        for(int x=0;x<dim.x();x++) for(int y=0;y<dim.y();y++) for(int z=0;z<dim.z();z++)
            grid.remove(new GridPosition(o.x()+x, o.y()+y, o.z()+z));
    }

    public PlacedMachine getMachineAt(UUID id, GridPosition pos) {
        Map<GridPosition, PlacedMachine> g = playerGrids.get(id);
        return g!=null ? g.get(pos) : null;
    }

    public boolean isAreaClear(UUID id, GridPosition o, Vector3Int dim) {
        Map<GridPosition, PlacedMachine> g = playerGrids.get(id);
        if(g==null) return true;
        for(int x=0;x<dim.x();x++) for(int y=0;y<dim.y();y++) for(int z=0;z<dim.z();z++)
            if(g.containsKey(new GridPosition(o.x()+x, o.y()+y, o.z()+z))) return false;
        return true;
    }

    public void tick(long t) {
        List<PlacedMachine> snap; synchronized(tickingMachines){ snap = new ArrayList<>(tickingMachines); }
        snap.forEach(m -> m.tick(t));
    }

    public Map<GridPosition, PlacedMachine> getSnapshot(UUID id) {
        Map<GridPosition, PlacedMachine> g = playerGrids.get(id);
        return g!=null?new HashMap<>(g):Collections.emptyMap();
    }

    public Map<GridPosition, MatterColor> getTerrainResources(UUID id) { return playerResources.getOrDefault(id, Collections.emptyMap()); }
    public BlockRegistry getBlockRegistry() { return blockRegistry; }
}