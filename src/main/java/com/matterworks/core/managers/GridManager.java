package com.matterworks.core.managers;

import com.matterworks.core.common.Direction;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.domain.factory.MachineFactory;
import com.matterworks.core.domain.machines.BlockRegistry;
import com.matterworks.core.domain.machines.DrillMachine;
import com.matterworks.core.domain.machines.IGridComponent;
import com.matterworks.core.domain.machines.PlacedMachine;
import com.matterworks.core.domain.matter.MatterColor;
import com.matterworks.core.domain.shop.MarketManager;
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

    private final Map<GridPosition, IGridComponent> globalGrid = new ConcurrentHashMap<>();
    private final List<PlacedMachine> tickingMachines = new ArrayList<>();
    private final ExecutorService ioExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<GridPosition, MatterColor> terrainResources = new ConcurrentHashMap<>();

    public GridManager(IRepository repository, IWorldAccess worldAdapter, BlockRegistry registry) {
        this.repository = repository;
        this.worldAdapter = worldAdapter;
        this.blockRegistry = registry;
        this.marketManager = new MarketManager(repository);
        generateMockTerrain();
    }

    private void generateMockTerrain() {
        System.out.println("🌍 Generazione Vene Risorse (Mock)...");
        terrainResources.put(new GridPosition(10, 0, 10), MatterColor.RAW);
        terrainResources.put(new GridPosition(10, 0, 9), MatterColor.RAW);
        terrainResources.put(new GridPosition(11, 0, 10), MatterColor.RED);
        terrainResources.put(new GridPosition(12, 0, 10), MatterColor.BLUE);
    }

    public Map<GridPosition, MatterColor> getTerrainResources() {
        return Collections.unmodifiableMap(terrainResources);
    }

    public void loadPlotFromDB(UUID ownerId) {
        ioExecutor.submit(() -> {
            try {
                List<PlotObject> dtos = repository.loadPlotMachines(ownerId);
                for (PlotObject dto : dtos) {
                    PlacedMachine machine = MachineFactory.createFromModel(dto, ownerId);
                    if (machine != null) {
                        internalAddMachine(machine);
                    }
                }
                System.out.println("✅ Plot loaded for " + ownerId + ": " + dtos.size() + " machines.");
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    public boolean placeMachine(UUID ownerId, GridPosition pos, String typeId) {
        Vector3Int dim = blockRegistry.getDimensions(typeId);

        if (!isAreaClear(pos, dim)) {
            System.out.println("⚠️ Area ostruita per " + typeId + " a " + pos);
            return false;
        }

        MatterColor resourceUnderDrill = null;
        if (typeId.equals("drill_mk1")) {
            if (pos.y() != 0) return false;
            resourceUnderDrill = terrainResources.get(pos);
            if (resourceUnderDrill == null) return false;
        }

        PlotObject newDto = new PlotObject(null, null, pos.x(), pos.y(), pos.z(), typeId, null);
        PlacedMachine newMachine = MachineFactory.createFromModel(newDto, ownerId);

        if (newMachine == null) return false;

        if (newMachine instanceof DrillMachine drill && resourceUnderDrill != null) {
            drill.setResourceToMine(resourceUnderDrill);
        }

        // 1. Aggiungi alla memoria
        internalAddMachine(newMachine);
        newMachine.onPlace(worldAdapter);

        // 2. SALVATAGGIO DB IMMEDIATO (Insert)
        Long dbId = repository.createMachine(ownerId, newMachine);
        if (dbId != null) {
            newMachine.setDbId(dbId);
            System.out.println("💾 DB: Nuova macchina salvata ID: " + dbId);
        } else {
            System.err.println("❌ DB: Fallito salvataggio macchina!");
        }

        return true;
    }

    public boolean rotateMachine(GridPosition pos, Direction newDir) {
        PlacedMachine machine = getMachineAt(pos);
        if (machine == null) return false;

        Direction oldDir = machine.getOrientation();
        if (oldDir == newDir) return true;

        removeFromGridMap(machine);
        machine.setOrientation(newDir);
        Vector3Int newDim = machine.getDimensions();

        if (isAreaClear(pos, newDim)) {
            addToGridMap(machine);
            return true;
        } else {
            machine.setOrientation(oldDir);
            addToGridMap(machine);
            System.out.println("⚠️ Rotazione bloccata: Collisione.");
            return false;
        }
    }

    public void removeComponent(GridPosition pos) {
        PlacedMachine target = getMachineAt(pos);
        if (target == null) return;

        if (target.getDbId() != null) repository.deleteMachine(target.getDbId());
        removeFromGridMap(target);
        synchronized (tickingMachines) { tickingMachines.remove(target); }
        target.onRemove();
    }

    private void internalAddMachine(PlacedMachine machine) {
        machine.setGridContext(this);
        addToGridMap(machine);
        synchronized (tickingMachines) { tickingMachines.add(machine); }
    }

    private void addToGridMap(PlacedMachine m) {
        Vector3Int dim = m.getDimensions();
        GridPosition origin = m.getPos();
        for (int x = 0; x < dim.x(); x++) {
            for (int y = 0; y < dim.y(); y++) {
                for (int z = 0; z < dim.z(); z++) {
                    globalGrid.put(new GridPosition(origin.x() + x, origin.y() + y, origin.z() + z), m);
                }
            }
        }
    }

    private void removeFromGridMap(PlacedMachine m) {
        Vector3Int dim = m.getDimensions();
        GridPosition origin = m.getPos();
        for (int x = 0; x < dim.x(); x++) {
            for (int y = 0; y < dim.y(); y++) {
                for (int z = 0; z < dim.z(); z++) {
                    globalGrid.remove(new GridPosition(origin.x() + x, origin.y() + y, origin.z() + z));
                }
            }
        }
    }

    public boolean isAreaClear(GridPosition origin, Vector3Int dim) {
        for (int x = 0; x < dim.x(); x++) {
            for (int y = 0; y < dim.y(); y++) {
                for (int z = 0; z < dim.z(); z++) {
                    if (globalGrid.containsKey(new GridPosition(origin.x() + x, origin.y() + y, origin.z() + z))) return false;
                }
            }
        }
        return true;
    }

    public PlacedMachine getMachineAt(GridPosition pos) {
        IGridComponent comp = globalGrid.get(pos);
        return (comp instanceof PlacedMachine pm) ? pm : null;
    }

    public MarketManager getMarketManager() { return marketManager; }

    public void tick(long currentTick) {
        List<PlacedMachine> snapshot;
        synchronized (tickingMachines) { snapshot = new ArrayList<>(tickingMachines); }
        snapshot.forEach(m -> m.tick(currentTick));
    }

    public Map<GridPosition, PlacedMachine> getSnapshot() {
        Map<GridPosition, PlacedMachine> uniqueMachines = new HashMap<>();
        synchronized (tickingMachines) {
            for (PlacedMachine m : tickingMachines) uniqueMachines.put(m.getPos(), m);
        }
        return Collections.unmodifiableMap(uniqueMachines);
    }
}