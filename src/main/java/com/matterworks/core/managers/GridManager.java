package com.matterworks.core.managers;

import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.domain.factory.MachineFactory;
import com.matterworks.core.domain.machines.BlockRegistry;
import com.matterworks.core.domain.machines.IGridComponent;
import com.matterworks.core.domain.machines.PlacedMachine;
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

    public GridManager(IRepository repository, IWorldAccess worldAdapter, BlockRegistry registry) {
        this.repository = repository;
        this.worldAdapter = worldAdapter;
        this.blockRegistry = registry;
        this.marketManager = new MarketManager(repository);
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

        PlotObject newDto = new PlotObject(null, null, pos.x(), pos.y(), pos.z(), typeId, null);
        PlacedMachine newMachine = MachineFactory.createFromModel(newDto, ownerId);

        if (newMachine == null) return false;

        internalAddMachine(newMachine);
        newMachine.onPlace(worldAdapter);
        return true;
    }

    private void internalAddMachine(PlacedMachine machine) {
        machine.setGridContext(this);

        Vector3Int dim = machine.getDimensions();
        GridPosition origin = machine.getPos();

        for (int x = 0; x < dim.x(); x++) {
            for (int y = 0; y < dim.y(); y++) {
                for (int z = 0; z < dim.z(); z++) {
                    GridPosition occupiedPos = new GridPosition(
                            origin.x() + x, origin.y() + y, origin.z() + z
                    );
                    globalGrid.put(occupiedPos, machine);
                }
            }
        }

        synchronized (tickingMachines) {
            tickingMachines.add(machine);
        }
    }

    // NEW: Logica di rimozione completa (DB + RAM)
    public void removeComponent(GridPosition pos) {
        // 1. Identifica la macchina bersaglio (anche se clicchi su un pezzo del multiblocco)
        PlacedMachine target = getMachineAt(pos);

        if (target == null) {
            return; // Click a vuoto
        }

        System.out.println("🗑️ Removing: " + target.getTypeId() + " (DB_ID: " + target.getDbId() + ")");

        // 2. Cancellazione dal DB
        // Se dbId è null, significa che non è stata ancora salvata (è solo in RAM),
        // quindi non serve delete SQL.
        if (target.getDbId() != null) {
            repository.deleteMachine(target.getDbId());
        }

        // 3. Pulizia RAM (Griglia)
        Vector3Int dim = target.getDimensions();
        GridPosition origin = target.getPos();

        // Rimuoviamo TUTTE le celle occupate (utile per Nexus 3x3x3)
        for (int x = 0; x < dim.x(); x++) {
            for (int y = 0; y < dim.y(); y++) {
                for (int z = 0; z < dim.z(); z++) {
                    GridPosition occupiedPos = new GridPosition(
                            origin.x() + x, origin.y() + y, origin.z() + z
                    );
                    globalGrid.remove(occupiedPos);
                }
            }
        }

        // 4. Stop Logic (Rimuovi dalla lista dei tick)
        synchronized (tickingMachines) {
            tickingMachines.remove(target);
        }

        target.onRemove();
    }

    public PlacedMachine getMachineAt(GridPosition pos) {
        IGridComponent comp = globalGrid.get(pos);
        if (comp instanceof PlacedMachine pm) {
            return pm;
        }
        return null;
    }

    public MarketManager getMarketManager() {
        return marketManager;
    }

    public boolean isAreaClear(GridPosition origin, Vector3Int dim) {
        for (int x = 0; x < dim.x(); x++) {
            for (int y = 0; y < dim.y(); y++) {
                for (int z = 0; z < dim.z(); z++) {
                    GridPosition checkPos = new GridPosition(origin.x() + x, origin.y() + y, origin.z() + z);
                    if (globalGrid.containsKey(checkPos)) return false;
                }
            }
        }
        return true;
    }

    public void tick(long currentTick) {
        // Creiamo una copia thread-safe per evitare ConcurrentModificationException
        // se una macchina si cancella da sola o viene cancellata durante il tick
        List<PlacedMachine> snapshot;
        synchronized (tickingMachines) {
            snapshot = new ArrayList<>(tickingMachines);
        }
        snapshot.forEach(m -> m.tick(currentTick));
    }

    public Map<GridPosition, PlacedMachine> getSnapshot() {
        Map<GridPosition, PlacedMachine> uniqueMachines = new HashMap<>();
        synchronized (tickingMachines) {
            for (PlacedMachine m : tickingMachines) {
                uniqueMachines.put(m.getPos(), m);
            }
        }
        return Collections.unmodifiableMap(uniqueMachines);
    }
}