package com.matterworks.core.managers;

import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.domain.factory.MachineFactory;
import com.matterworks.core.domain.machines.BlockRegistry;
import com.matterworks.core.domain.machines.DrillMachine; // Import
import com.matterworks.core.domain.machines.IGridComponent;
import com.matterworks.core.domain.machines.PlacedMachine;
import com.matterworks.core.domain.matter.MatterColor; // Import
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

    // --- NUOVO: MAPPA DELLE RISORSE DEL TERRENO ---
    // Mappa Coordinate 2D (x, z) -> Tipo di Risorsa
    // Usiamo una String key "x,z" per semplicità o una mappa dedicata.
    // Per coerenza col progetto, usiamo una Map<GridPosition, MatterColor> dove y è sempre 0.
    private final Map<GridPosition, MatterColor> terrainResources = new ConcurrentHashMap<>();

    public GridManager(IRepository repository, IWorldAccess worldAdapter, BlockRegistry registry) {
        this.repository = repository;
        this.worldAdapter = worldAdapter;
        this.blockRegistry = registry;
        this.marketManager = new MarketManager(repository);

        // Generiamo il terreno finto (in futuro verra caricato dal PlotDAO)
        generateMockTerrain();
    }

    private void generateMockTerrain() {
        System.out.println("🌍 Generazione Vene Risorse (Mock)...");

        // Vena RAW (Grigia) sotto la zona di partenza (x=10, z=10)
        terrainResources.put(new GridPosition(10, 0, 10), MatterColor.RAW);
        terrainResources.put(new GridPosition(10, 0, 11), MatterColor.RAW);
        terrainResources.put(new GridPosition(11, 0, 10), MatterColor.RAW);

        // Vena RED (Rossa) un po' più a sinistra
        terrainResources.put(new GridPosition(5, 0, 5), MatterColor.RED);
        terrainResources.put(new GridPosition(6, 0, 5), MatterColor.RED);

        // Vena BLUE (Blu) un po' più a destra
        terrainResources.put(new GridPosition(15, 0, 15), MatterColor.BLUE);
    }

    // Metodo pubblico per la GUI (per disegnare le risorse a terra)
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

        // Controllo generico collisioni
        if (!isAreaClear(pos, dim)) {
            System.out.println("⚠️ Area ostruita per " + typeId + " a " + pos);
            return false;
        }

        // --- NUOVO: CONTROLLO SPECIFICO PER TRIVELLE ---
        MatterColor resourceUnderDrill = null;

        if (typeId.equals("drill_mk1")) {
            // 1. Deve essere a terra (Y=0)
            if (pos.y() != 0) {
                System.out.println("⛔ ERRORE: La trivella può essere piazzata solo a terra (Y=0)!");
                return false;
            }
            // 2. Deve esserci una risorsa sotto
            resourceUnderDrill = terrainResources.get(pos);
            if (resourceUnderDrill == null) {
                System.out.println("⛔ ERRORE: Nessuna risorsa da estrarre qui!");
                return false;
            }
        }
        // ------------------------------------------------

        PlotObject newDto = new PlotObject(null, null, pos.x(), pos.y(), pos.z(), typeId, null);
        PlacedMachine newMachine = MachineFactory.createFromModel(newDto, ownerId);

        if (newMachine == null) return false;

        // --- NUOVO: CONFIGURA LA TRIVELLA ---
        if (newMachine instanceof DrillMachine drill && resourceUnderDrill != null) {
            drill.setResourceToMine(resourceUnderDrill);
            System.out.println("⛏️ Trivella configurata per estrarre: " + resourceUnderDrill);
        }

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

    public void removeComponent(GridPosition pos) {
        PlacedMachine target = getMachineAt(pos);
        if (target == null) return;

        System.out.println("🗑️ Removing: " + target.getTypeId() + " (DB_ID: " + target.getDbId() + ")");

        if (target.getDbId() != null) {
            repository.deleteMachine(target.getDbId());
        }

        Vector3Int dim = target.getDimensions();
        GridPosition origin = target.getPos();

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