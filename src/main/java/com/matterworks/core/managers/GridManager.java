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

    // --- NUOVO: LOGICA DI BAILOUT (SOS) ---
    public boolean attemptBailout(UUID ownerId) {
        // 1. Controlla i soldi
        PlayerProfile player = repository.loadPlayerProfile(ownerId);
        if (player == null) return false;

        if (player.getMoney() >= 500.0) {
            System.out.println("❌ BAILOUT NEGATO: Hai già troppi soldi (" + player.getMoney() + ")");
            return false;
        }

        // 2. Conta asset totali (Mondo + Inventario)
        int drills = countAsset(ownerId, "drill_mk1");
        int belts  = countAsset(ownerId, "conveyor_belt");
        int nexus  = countAsset(ownerId, "nexus_core");

        System.out.println("🔍 Audit SOS per " + ownerId + ": Drills=" + drills + ", Belts=" + belts + ", Nexus=" + nexus);

        // 3. Regola: Se manchi di un pezzo fondamentale per la produzione base
        boolean isStuck = (drills < 1) || (belts < 2) || (nexus < 1);

        if (isStuck) {
            System.out.println("🚑 BAILOUT APPROVATO: Giocatore bloccato. Erogazione fondi...");
            player.modifyMoney(500.0);
            repository.savePlayerProfile(player);
            return true;
        } else {
            System.out.println("❌ BAILOUT NEGATO: Hai abbastanza macchine per ripartire.");
            return false;
        }
    }

    private int countAsset(UUID ownerId, String typeId) {
        // A. Conta nel mondo (Memoria)
        int worldCount = 0;
        Map<GridPosition, PlacedMachine> grid = playerGrids.get(ownerId);
        if (grid != null) {
            worldCount = (int) grid.values().stream()
                    .filter(m -> m.getTypeId().equals(typeId))
                    .count();
        }

        // B. Conta nell'inventario (DB)
        int invCount = repository.getInventoryItemCount(ownerId, typeId);

        return worldCount + invCount;
    }

    // ... (Tutti gli altri metodi rimangono invariati) ...
    // resetUserPlot, loadPlotFromDB, placeMachine, removeComponent, etc.
    // Assicurati di copiare tutto il codice precedente qui sotto per non rompere nulla.

    // [COPIA QUI IL RESTO DELLA CLASSE GRIDMANAGER DAL PASSO PRECEDENTE]
    // (Ometti solo se hai già il file aggiornato, ma è importante che ci sia tutto)

    public void resetUserPlot(UUID ownerId) {
        ioExecutor.submit(() -> {
            System.out.println("⚠️ RESET RICHIESTO PER " + ownerId);
            unloadPlot(ownerId);
            if (repository instanceof MariaDBAdapter dbAdapter) {
                dbAdapter.clearPlotData(ownerId);
            }
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
                        if (resources.isEmpty()) {
                            generateDefaultResources(dbAdapter, plotId, resources);
                        }
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

    public boolean placeMachine(UUID ownerId, GridPosition pos, String typeId, Direction orientation) {
        var stats = blockRegistry.getStats(typeId);
        double price = stats.basePrice();
        Vector3Int baseDim = stats.dimensions();

        PlayerProfile player = repository.loadPlayerProfile(ownerId);
        if (player == null || player.getMoney() < price) {
            System.out.println("💸 Fondi Insufficienti! Serve $" + price);
            return false;
        }

        Vector3Int effectiveDim = baseDim;
        if (orientation == Direction.EAST || orientation == Direction.WEST) {
            effectiveDim = new Vector3Int(baseDim.z(), baseDim.y(), baseDim.x());
        }

        if (!isAreaClear(ownerId, pos, effectiveDim)) return false;

        MatterColor resourceUnderDrill = null;
        if (typeId.equals("drill_mk1")) {
            if (pos.y() != 0) return false;
            Map<GridPosition, MatterColor> myResources = playerResources.get(ownerId);
            if (myResources != null) resourceUnderDrill = myResources.get(pos);
            if (resourceUnderDrill == null) return false;
        }

        PlotObject newDto = new PlotObject(null, null, pos.x(), pos.y(), pos.z(), typeId, null);
        PlacedMachine newMachine = MachineFactory.createFromModel(newDto, ownerId);
        if (newMachine == null) return false;

        newMachine.setOrientation(orientation);
        if (newMachine instanceof DrillMachine drill && resourceUnderDrill != null) {
            drill.setResourceToMine(resourceUnderDrill);
        }

        player.modifyMoney(-price);
        repository.savePlayerProfile(player);

        internalAddMachine(ownerId, newMachine);
        newMachine.onPlace(worldAdapter);

        Long dbId = repository.createMachine(ownerId, newMachine);
        if (dbId != null) newMachine.setDbId(dbId);

        return true;
    }

    public void removeComponent(UUID ownerId, GridPosition pos) {
        PlacedMachine target = getMachineAt(ownerId, pos);
        if (target == null) return;

        double refund = blockRegistry.getPrice(target.getTypeId());
        if (refund > 0) {
            PlayerProfile player = repository.loadPlayerProfile(ownerId);
            if (player != null) {
                player.modifyMoney(refund);
                repository.savePlayerProfile(player);
            }
        }

        if (target.getDbId() != null) repository.deleteMachine(target.getDbId());
        removeFromGridMap(ownerId, target);
        synchronized (tickingMachines) { tickingMachines.remove(target); }
        target.onRemove();
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