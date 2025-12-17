package com.matterworks.core.managers;

import com.matterworks.core.common.Direction;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.domain.factory.MachineFactory;
import com.matterworks.core.domain.machines.BlockRegistry;
import com.matterworks.core.domain.machines.DrillMachine;
import com.matterworks.core.domain.machines.PlacedMachine;
import com.matterworks.core.domain.matter.MatterColor;
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

    // --- NUOVO METODO RESET ---
    public void resetUserPlot(UUID ownerId) {
        ioExecutor.submit(() -> {
            System.out.println("⚠️ RESET RICHIESTO PER " + ownerId);

            // 1. Pulisce memoria
            unloadPlot(ownerId);

            // 2. Pulisce DB (Macchine + Risorse)
            if (repository instanceof MariaDBAdapter dbAdapter) {
                dbAdapter.clearPlotData(ownerId);
            }

            // 3. Ricarica (Questo innescherà la generazione di nuove risorse RANDOM)
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

                        // SE VUOTO (Nuovo Plot o Appena Resettato) -> GENERA RISORSE
                        if (resources.isEmpty()) {
                            System.out.println("🌍 Generazione NUOVE risorse per Plot " + plotId);
                            generateDefaultResources(dbAdapter, plotId, resources);
                        }

                        playerResources.put(ownerId, resources);
                        System.out.println("💎 Risorse caricate: " + resources.size() + " vene.");
                    }
                }

                // Carica macchine (dopo reset sarà vuoto)
                List<PlotObject> dtos = repository.loadPlotMachines(ownerId);
                for (PlotObject dto : dtos) {
                    PlacedMachine machine = MachineFactory.createFromModel(dto, ownerId);
                    if (machine != null) internalAddMachine(ownerId, machine);
                }
            } catch (Exception e) { e.printStackTrace(); }
        });
    }

    // AGGIORNATO: Generazione Randomica ("Re-enroll")
    private void generateDefaultResources(MariaDBAdapter db, Long plotId, Map<GridPosition, MatterColor> cache) {
        Random rnd = new Random();

        // 2-3 Vene di RAW
        for (int i = 0; i < 3; i++) {
            createResource(db, plotId, cache, rnd.nextInt(15) + 2, 0, rnd.nextInt(15) + 2, MatterColor.RAW);
        }
        // 1 Vena ROSSA
        createResource(db, plotId, cache, rnd.nextInt(15) + 2, 0, rnd.nextInt(15) + 2, MatterColor.RED);
        // 1 Vena BLU
        createResource(db, plotId, cache, rnd.nextInt(15) + 2, 0, rnd.nextInt(15) + 2, MatterColor.BLUE);
    }

    private void createResource(MariaDBAdapter db, Long plotId, Map<GridPosition, MatterColor> cache, int x, int y, int z, MatterColor type) {
        // Evita sovrapposizioni nella generazione random
        if (cache.containsKey(new GridPosition(x, y, z))) return;

        db.saveResource(plotId, x, z, type);
        cache.put(new GridPosition(x, y, z), type);
    }

    private void unloadPlot(UUID ownerId) {
        playerGrids.remove(ownerId);
        playerResources.remove(ownerId);
        synchronized (tickingMachines) {
            tickingMachines.removeIf(m -> m.getOwnerId().equals(ownerId));
        }
    }

    // ... (Il resto dei metodi placeMachine, internalAddMachine, etc. RIMANE UGUALE) ...
    // Assicurati di copiare i metodi esistenti dal file precedente.

    public boolean placeMachine(UUID ownerId, GridPosition pos, String typeId, Direction orientation) {
        Vector3Int baseDim = blockRegistry.getDimensions(typeId);
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
            if (resourceUnderDrill == null) {
                System.out.println("⚠️ Nessuna risorsa sotto la trivella a " + pos);
                return false;
            }
        }

        PlotObject newDto = new PlotObject(null, null, pos.x(), pos.y(), pos.z(), typeId, null);
        PlacedMachine newMachine = MachineFactory.createFromModel(newDto, ownerId);
        if (newMachine == null) return false;

        newMachine.setOrientation(orientation);
        if (newMachine instanceof DrillMachine drill && resourceUnderDrill != null) {
            drill.setResourceToMine(resourceUnderDrill);
        }

        internalAddMachine(ownerId, newMachine);
        newMachine.onPlace(worldAdapter);

        Long dbId = repository.createMachine(ownerId, newMachine);
        if (dbId != null) {
            newMachine.setDbId(dbId);
        }

        return true;
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

    public void removeComponent(UUID ownerId, GridPosition pos) {
        PlacedMachine target = getMachineAt(ownerId, pos);
        if (target == null) return;
        if (target.getDbId() != null) repository.deleteMachine(target.getDbId());
        removeFromGridMap(ownerId, target);
        synchronized (tickingMachines) { tickingMachines.remove(target); }
        target.onRemove();
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