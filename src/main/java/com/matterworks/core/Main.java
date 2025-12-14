package com.matterworks.core;

import com.matterworks.core.common.Direction;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.database.dao.MachineDefinitionDAO;
import com.matterworks.core.database.dao.PlayerDAO;
import com.matterworks.core.database.dao.PlotDAO;
import com.matterworks.core.domain.machines.BlockRegistry;
import com.matterworks.core.domain.machines.PlacedMachine;
import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.infrastructure.CoreConfig;
import com.matterworks.core.infrastructure.MariaDBAdapter;
import com.matterworks.core.infrastructure.swing.MatterWorksGUI; // Import della GUI
import com.matterworks.core.managers.GridManager;
import com.matterworks.core.managers.WorldIntegrityValidator;
import com.matterworks.core.ports.IRepository;
import com.matterworks.core.ports.IWorldAccess;
import com.matterworks.core.synchronization.FactoryLoop;
import com.matterworks.core.synchronization.GridSaverService;

import javax.swing.SwingUtilities;
import java.util.UUID;

public class Main {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("🏭 MatterWorks Core (Swing Edition) Starting...");

        // ---------------------------------------------------------
        // FASE 0: CONFIGURAZIONE & INFRASTRUTTURA
        // ---------------------------------------------------------
        CoreConfig.load();

        String url = "jdbc:mariadb://localhost:3306/matterworks_core?allowPublicKeyRetrieval=true&useSSL=false";
        DatabaseManager dbManager = new DatabaseManager(url, "Noctino52", "Yy72s7mRnVs3");

        // World Adapter "Mock" (Verrà sostituito da Hytale, ora serve per logica interna)
        IWorldAccess world = new MockWorld();

        // ---------------------------------------------------------
        // FASE 1: REGISTRY & DEFINIZIONI
        // ---------------------------------------------------------
        MachineDefinitionDAO defDao = new MachineDefinitionDAO(dbManager);
        BlockRegistry blockRegistry = new BlockRegistry(world, defDao);
        blockRegistry.loadFromDatabase(); // Carica le dimensioni (es. Nexus = 3x3x3)

        // ---------------------------------------------------------
        // FASE 2: VALIDAZIONE INTEGRITÀ (Safety First)
        // ---------------------------------------------------------
        WorldIntegrityValidator validator = new WorldIntegrityValidator(dbManager, blockRegistry);
        if (!validator.validateWorldIntegrity()) {
            System.err.println("🚨 ATTENZIONE: Il mondo contiene collisioni! Controlla il DB.");
            Thread.sleep(2000);
        }

        // ---------------------------------------------------------
        // FASE 3: WIRING DEL CORE (Hexagonal Setup)
        // ---------------------------------------------------------
        IRepository repository = new MariaDBAdapter(dbManager);

        // GridManager: Il cervello che gestisce la griglia
        GridManager gridManager = new GridManager(repository, world, blockRegistry);

        // SaverService: Salva periodicamente
        GridSaverService saverService = new GridSaverService(gridManager, repository);

        // FactoryLoop: Il cuore pulsante (Tick rate)
        FactoryLoop gameLoop = new FactoryLoop(gridManager);
        gameLoop.start();

        // ---------------------------------------------------------
        // FASE 4: SETUP PLAYER & SCENARIO
        // ---------------------------------------------------------
        UUID playerUuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        ensurePlayerExists(dbManager, playerUuid);
        ensurePlayerHasPlot(dbManager, playerUuid);

        System.out.println("📥 Caricamento Plot dal Database...");
        gridManager.loadPlotFromDB(playerUuid);
        Thread.sleep(500); // Attesa IO

        // --- SETUP SCENARIO: Drill -> Belt -> Nexus ---
        setupScenario(gridManager, blockRegistry, playerUuid);

// ---------------------------------------------------------
        // FASE 5: AVVIO GUI (SWING ADAPTER)
        // ---------------------------------------------------------
        System.out.println("🖥️ Avvio Interfaccia Grafica di Debug...");

        SwingUtilities.invokeLater(() -> {
            new MatterWorksGUI(
                    gridManager,
                    blockRegistry,
                    playerUuid,
                    // 1. Azione di Salvataggio (Lambda)
                    () -> {
                        System.out.println("💾 Manual Save Requested via GUI...");
                        saverService.autoSaveTask();
                    },
                    // 2. Provider dei Soldi (Lambda)
                    () -> {
                        PlayerProfile p = repository.loadPlayerProfile(playerUuid);
                        return (p != null) ? p.getMoney() : 0.0;
                    }
            );
        });

        // ---------------------------------------------------------
        // FASE 6: LOOP DI MANTENIMENTO
        // ---------------------------------------------------------
        System.out.println("\n--- 🟢 SISTEMA ONLINE ---");
        System.out.println("    Monitora la finestra grafica per vedere la fabbrica.");
        System.out.println("    Premi STOP nell'IDE per chiudere tutto.\n");

        int ticks = 0;
        while (true) {
            Thread.sleep(1000);
            ticks++;

            // Ogni 10 secondi eseguiamo un autosave
            if (ticks % 10 == 0) {
                System.out.println("💾 AutoSave Triggered...");
                saverService.autoSaveTask();

                // Monitoraggio Saldo (opzionale)
                PlayerProfile p = repository.loadPlayerProfile(playerUuid);
                if (p != null) {
                    System.out.println("💰 Saldo Player: " + p.getMoney() + "$");
                }
            }
        }
    }

    // --- HELPER METODI ---

    private static void setupScenario(GridManager gm, BlockRegistry reg, UUID owner) {
        // 1. TRIVELLA a (10, 64, 10). Output NORD -> (10, 64, 9)
        GridPosition drillPos = new GridPosition(10, 64, 10);
        if (gm.isAreaClear(drillPos, reg.getDimensions("drill_mk1"))) {
            System.out.println("🆕 Piazzamento Trivella...");
            gm.placeMachine(owner, drillPos, "drill_mk1");
            updateOrientation(gm, drillPos, Direction.NORTH);
        }

        // 2. NASTRO a (10, 64, 9). Riceve da 10, Output NORD -> (10, 64, 8)
        GridPosition beltPos = new GridPosition(10, 64, 9);
        if (gm.isAreaClear(beltPos, reg.getDimensions("conveyor_belt"))) {
            System.out.println("🆕 Piazzamento Nastro...");
            gm.placeMachine(owner, beltPos, "conveyor_belt");
            updateOrientation(gm, beltPos, Direction.NORTH);
        }

        // 3. NEXUS a (10, 64, 6).
        // Dimensioni: 3x3x3. Occupa X[10-12], Y[64-66], Z[6-8].
        // Il "retro" del Nexus è a Z=8. Il nastro a Z=9 spinge verso Z=8.
        GridPosition nexusPos = new GridPosition(10, 64, 6);
        if (gm.isAreaClear(nexusPos, reg.getDimensions("nexus_core"))) {
            System.out.println("🆕 Piazzamento Nexus Core...");
            gm.placeMachine(owner, nexusPos, "nexus_core");
            // L'orientamento del Nexus è meno rilevante, ma lo mettiamo a SUD per "guardare" la fabbrica
            updateOrientation(gm, nexusPos, Direction.SOUTH);
        } else {
            // Se non è clear, controlliamo se è perché c'è già il nexus
            PlacedMachine m = gm.getMachineAt(nexusPos);
            if (m != null && m.getTypeId().equals("nexus_core")) {
                System.out.println("✅ Nexus Core già presente.");
            } else {
                System.out.println("⚠️ Impossibile piazzare Nexus: Area occupata.");
            }
        }
    }

    private static void updateOrientation(GridManager gm, GridPosition pos, Direction dir) {
        PlacedMachine pm = gm.getMachineAt(pos);
        if (pm != null) {
            pm.setOrientation(dir);
        }
    }

    private static void ensurePlayerExists(DatabaseManager db, UUID uuid) {
        PlayerDAO playerDao = new PlayerDAO(db);
        if (playerDao.load(uuid) == null) {
            PlayerProfile p = new PlayerProfile(uuid);
            p.setUsername("Noctino_Dev");
            p.setMoney(1000.0);
            playerDao.save(p);
        }
    }

    private static void ensurePlayerHasPlot(DatabaseManager db, UUID owner) {
        PlotDAO plotDao = new PlotDAO(db);
        if (plotDao.findPlotIdByOwner(owner) == null) {
            plotDao.createPlot(owner, 1, 0, 0);
        }
    }

    // Mock World Adapter (usato solo per validazione base, la grafica la fa Swing)
    static class MockWorld implements IWorldAccess {
        @Override public void setBlock(GridPosition pos, String blockId) {}
        @Override public boolean isBlockSolid(GridPosition pos) { return true; }
        @Override public void createVisuals(GridPosition pos, String visualId) {}
        @Override public Vector3Int fetchExternalBlockDimensions(String blockId) { return Vector3Int.one(); }
    }
}