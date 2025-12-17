package com.matterworks.core.managers;

import com.matterworks.core.common.Direction;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.domain.factory.MachineFactory;
import com.matterworks.core.domain.machines.BlockRegistry;
import com.matterworks.core.domain.machines.DrillMachine;
import com.matterworks.core.domain.machines.PlacedMachine;
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

    // --- NUOVO: SISTEMA SHOP (Soldi -> Item) ---
    public boolean buyItem(UUID playerId, String itemId, int amount) {
        var stats = blockRegistry.getStats(itemId);
        double totalCost = stats.basePrice() * amount;

        PlayerProfile p = repository.loadPlayerProfile(playerId);
        if (p == null) return false;

        // Se Admin, compra gratis (opzionale, ma utile per debug)
        if (!p.isAdmin() && p.getMoney() < totalCost) {
            System.out.println("💸 Fondi insufficienti per " + amount + "x " + itemId);
            return false;
        }

        if (!p.isAdmin()) {
            p.modifyMoney(-totalCost);
            repository.savePlayerProfile(p);
        }

        // Aggiungi all'inventario
        repository.modifyInventoryItem(playerId, itemId, amount);
        System.out.println("🛒 SHOP: " + playerId + " ha comprato " + amount + "x " + itemId);
        return true;
    }

    // --- PIAZZAMENTO CON CONTROLLO INVENTARIO ---
    public boolean placeMachine(UUID ownerId, GridPosition pos, String typeId, Direction orientation) {
        // 1. Check Profilo e Rango
        PlayerProfile player = repository.loadPlayerProfile(ownerId);
        if (player == null) return false;
        boolean isAdmin = player.isAdmin();

        // 2. Check Inventario (Se NON Admin)
        if (!isAdmin) {
            int count = repository.getInventoryItemCount(ownerId, typeId);
            if (count <= 0) {
                System.out.println("🎒 Inventario vuoto! Ti serve: " + typeId);
                return false;
            }
        }

        // 3. Collisioni e Logica
        var stats = blockRegistry.getStats(typeId);
        Vector3Int baseDim = stats.dimensions();
        Vector3Int effectiveDim = baseDim;
        if (orientation == Direction.EAST || orientation == Direction.WEST) {
            effectiveDim = new Vector3Int(baseDim.z(), baseDim.y(), baseDim.x());
        }

        if (!isAreaClear(ownerId, pos, effectiveDim)) {
            System.out.println("⚠️ Area ostruita per " + typeId + " a " + pos);
            return false;
        }

        MatterColor resourceUnderDrill = null;
        if (typeId.equals("drill_mk1")) {
            if (pos.y() != 0) return false;
            Map<GridPosition, MatterColor> myResources = playerResources.get(ownerId);
            if (myResources != null) resourceUnderDrill = myResources.get(pos);
            if (resourceUnderDrill == null) return false;
        }

        // 4. Consumo Item (Se NON Admin)
        if (!isAdmin) {
            repository.modifyInventoryItem(ownerId, typeId, -1);
            System.out.println("🔻 Inventario: Usato 1x " + typeId);
        } else {
            System.out.println("🛡️ Admin Bypass: Piazzato " + typeId + " gratis.");
        }

        // 5. Creazione e Salvataggio
        PlotObject newDto = new PlotObject(null, null, pos.x(), pos.y(), pos.z(), typeId, null);
        PlacedMachine newMachine = MachineFactory.createFromModel(newDto, ownerId);
        if (newMachine == null) return false; // Should reimburse if failed later, but here is safe

        newMachine.setOrientation(orientation);
        if (newMachine instanceof DrillMachine drill && resourceUnderDrill != null) {
            drill.setResourceToMine(resourceUnderDrill);
        }

        internalAddMachine(ownerId, newMachine);
        newMachine.onPlace(worldAdapter);

        Long dbId = repository.createMachine(ownerId, newMachine);
        if (dbId != null) newMachine.setDbId(dbId);

        return true;
    }

    // --- RIMOZIONE CON RESTITUZIONE ITEM ---
    public void removeComponent(UUID ownerId, GridPosition pos) {
        PlacedMachine target = getMachineAt(ownerId, pos);
        if (target == null) return;

        // Restituisci SEMPRE l'item (anche agli admin, non fa male)
        repository.modifyInventoryItem(ownerId, target.getTypeId(), 1);
        System.out.println("🎒 Recuperato: 1x " + target.getTypeId());

        if (target.getDbId() != null) repository.deleteMachine(target.getDbId());
        removeFromGridMap(ownerId, target);
        synchronized (tickingMachines) { tickingMachines.remove(target); }
        target.onRemove();
    }

    // --- BAILOUT (SOS) ---
    public boolean attemptBailout(UUID ownerId) {
        PlayerProfile player = repository.loadPlayerProfile(ownerId);
        if (player == null || player.getMoney() >= 500.0) return false;

        int drills = countAsset(ownerId, "drill_mk1");
        int belts  = countAsset(ownerId, "conveyor_belt");
        int nexus  = countAsset(ownerId, "nexus_core");

        if (drills < 1 || belts < 2 || nexus < 1) {
            System.out.println("🚑 BAILOUT APPROVATO: Giocatore bloccato. Erogazione fondi...");
            player.modifyMoney(500.0);
            repository.savePlayerProfile(player);
            return true;
        }
        return false;
    }

    private int countAsset(UUID ownerId, String typeId) {
        int worldCount = 0;
        Map<GridPosition, PlacedMachine> grid = playerGrids.get(ownerId);
        if (grid != null) {
            worldCount = (int) grid.values().stream().filter(m -> m.getTypeId().equals(typeId)).count();
        }
        int invCount = repository.getInventoryItemCount(ownerId, typeId);
        return worldCount + invCount;
    }

    // ... (METODI STANDARD DI GESTIONE GRID - COPIARE DAL VECCHIO FILE) ...
    // resetUserPlot, loadPlotFromDB, generateDefaultResources, internalAddMachine, etc.

    public void resetUserPlot(UUID ownerId) {
        ioExecutor.submit(() -> {
            unloadPlot(ownerId);
            if (repository instanceof MariaDBAdapter dbAdapter) dbAdapter.clearPlotData(ownerId);
            loadPlotFromDB(ownerId);
        });
    }

    public void loadPlotFromDB(UUID ownerId) {
        ioExecutor.submit(() -> {
            try {
                unloadPlot(ownerId);
                if (repository instanceof MariaDBAdapter dbAdapter) {
                    Long plotId = dbAdapter.getPlotId(ownerId);
                    if (plotId != null) {
                        Map<GridPosition, MatterColor> resources = dbAdapter.loadResources(plotId);
                        if (resources.isEmpty()) generateDefaultResources(dbAdapter, plotId, resources);
                        playerResources.put(ownerId, resources);
                    }
                }
                List<PlotObject> dtos = repository.loadPlotMachines(ownerId);
                for (PlotObject dto : dtos) {
                    PlacedMachine machine = MachineFactory.createFromModel(dto, ownerId);
                    if (machine != null) internalAddMachine(ownerId, machine);
                }
            } catch (Exception e) { e.printStackTrace(); }
        });
    }

    private void generateDefaultResources(MariaDBAdapter db, Long plotId, Map<GridPosition, MatterColor> cache) {
        Random rnd = new Random();
        for (int i = 0; i < 3; i++) createResource(db, plotId, cache, rnd.nextInt(15) + 2, 0, rnd.nextInt(15) + 2, MatterColor.RAW);
        createResource(db, plotId, cache, rnd.nextInt(15) + 2, 0, rnd.nextInt(15) + 2, MatterColor.RED);
        createResource(db, plotId, cache, rnd.nextInt(15) + 2, 0, rnd.nextInt(15) + 2, MatterColor.BLUE);
    }

    private void createResource(MariaDBAdapter db, Long plotId, Map<GridPosition, MatterColor> cache, int x, int y, int z, MatterColor type) {
        if (cache.containsKey(new GridPosition(x, y, z))) return;
        db.saveResource(plotId, x, z, type);
        cache.put(new GridPosition(x, y, z), type);
    }

    private void unloadPlot(UUID ownerId) {
        playerGrids.remove(ownerId);
        playerResources.remove(ownerId);
        synchronized (tickingMachines) { tickingMachines.removeIf(m -> m.getOwnerId().equals(ownerId)); }
    }

    private void internalAddMachine(UUID ownerId, PlacedMachine machine) {
        machine.setGridContext(this);
        Map<GridPosition, PlacedMachine> myGrid = playerGrids.computeIfAbsent(ownerId, k -> new ConcurrentHashMap<>());
        Vector3Int dim = machine.getDimensions();
        GridPosition origin = machine.getPos();
        for (int x = 0; x < dim.x(); x++) {
            for (int y = 0; y < dim.y(); y++) {
                for (int z = 0; z < dim.z(); z++) {
                    myGrid.put(new GridPosition(origin.x() + x, origin.y() + y, origin.z() + z), machine);
                }
            }
        }
        tickingMachines.add(machine);
    }

    private void addToGridMap(UUID ownerId, PlacedMachine m) {
        Map<GridPosition, PlacedMachine> grid = playerGrids.get(ownerId);
        if (grid == null) return;
        Vector3Int dim = m.getDimensions();
        GridPosition origin = m.getPos();
        for (int x = 0; x < dim.x(); x++) {
            for (int y = 0; y < dim.y(); y++) {
                for (int z = 0; z < dim.z(); z++) {
                    grid.put(new GridPosition(origin.x() + x, origin.y() + y, origin.z() + z), m);
                }
            }
        }
    }

    private void removeFromGridMap(UUID ownerId, PlacedMachine m) {
        Map<GridPosition, PlacedMachine> grid = playerGrids.get(ownerId);
        if (grid == null) return;
        Vector3Int dim = m.getDimensions();
        GridPosition origin = m.getPos();
        for (int x = 0; x < dim.x(); x++) {
            for (int y = 0; y < dim.y(); y++) {
                for (int z = 0; z < dim.z(); z++) {
                    grid.remove(new GridPosition(origin.x() + x, origin.y() + y, origin.z() + z));
                }
            }
        }
    }

    public PlacedMachine getMachineAt(UUID ownerId, GridPosition pos) {
        Map<GridPosition, PlacedMachine> myGrid = playerGrids.get(ownerId);
        return myGrid != null ? myGrid.get(pos) : null;
    }

    public boolean isAreaClear(UUID ownerId, GridPosition origin, Vector3Int dim) {
        Map<GridPosition, PlacedMachine> myGrid = playerGrids.get(ownerId);
        if (myGrid == null) return true;
        for (int x = 0; x < dim.x(); x++) {
            for (int y = 0; y < dim.y(); y++) {
                for (int z = 0; z < dim.z(); z++) {
                    if (myGrid.containsKey(new GridPosition(origin.x() + x, origin.y() + y, origin.z() + z))) return false;
                }
            }
        }
        return true;
    }

    public boolean rotateMachine(GridPosition pos, Direction newDir, UUID ownerId) {
        PlacedMachine machine = getMachineAt(ownerId, pos);
        if (machine == null) return false;
        Direction oldDir = machine.getOrientation();
        if (oldDir == newDir) return true;
        removeFromGridMap(ownerId, machine);
        machine.setOrientation(newDir);
        Vector3Int newDim = machine.getDimensions();
        if (isAreaClear(ownerId, pos, newDim)) {
            addToGridMap(ownerId, machine);
            return true;
        } else {
            machine.setOrientation(oldDir);
            addToGridMap(ownerId, machine);
            return false;
        }
    }

    public void tick(long currentTick) {
        List<PlacedMachine> snapshot;
        synchronized (tickingMachines) { snapshot = new ArrayList<>(tickingMachines); }
        snapshot.forEach(m -> m.tick(currentTick));
    }

    public Map<GridPosition, PlacedMachine> getSnapshot(UUID ownerId) {
        Map<GridPosition, PlacedMachine> grid = playerGrids.get(ownerId);
        return grid != null ? new HashMap<>(grid) : Collections.emptyMap();
    }

    public Map<GridPosition, PlacedMachine> getAllMachinesSnapshot() {
        Map<GridPosition, PlacedMachine> all = new HashMap<>();
        for(var map : playerGrids.values()) { all.putAll(map); }
        return all;
    }

    public Map<GridPosition, MatterColor> getTerrainResources(UUID ownerId) {
        return playerResources.getOrDefault(ownerId, Collections.emptyMap());
    }

    public MarketManager getMarketManager() { return marketManager; }
}