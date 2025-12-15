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
import com.matterworks.core.infrastructure.swing.MatterWorksGUI;
import com.matterworks.core.managers.GridManager;
import com.matterworks.core.managers.WorldIntegrityValidator;
import com.matterworks.core.ports.IRepository;
import com.matterworks.core.ports.IWorldAccess;
import com.matterworks.core.synchronization.FactoryLoop;
import com.matterworks.core.synchronization.GridSaverService;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment; // IMPORT FONDAMENTALE PER IL CHECK
import java.util.UUID;

public class Main {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("🏭 MatterWorks Core Starting...");

        // ---------------------------------------------------------
        // FASE 0: CONFIGURAZIONE & INFRASTRUTTURA
        // ---------------------------------------------------------
        CoreConfig.load();

        // Nota: localhost va bene sia su PC (se hai il tunnel o db locale) sia su VPS (se docker espone su 127.0.0.1)
        String url = "jdbc:mariadb://localhost:3306/matterworks_core?allowPublicKeyRetrieval=true&useSSL=false";
        DatabaseManager dbManager = new DatabaseManager(url, "Noctino52", "Yy72s7mRnVs3");

        // World Adapter "Mock"
        IWorldAccess world = new MockWorld();

        // ---------------------------------------------------------
        // FASE 1: REGISTRY & DEFINIZIONI
        // ---------------------------------------------------------
        MachineDefinitionDAO defDao = new MachineDefinitionDAO(dbManager);
        BlockRegistry blockRegistry = new BlockRegistry(world, defDao);
        blockRegistry.loadFromDatabase();

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
        GridManager gridManager = new GridManager(repository, world, blockRegistry);
        GridSaverService saverService = new GridSaverService(gridManager, repository);

        // FactoryLoop: Il cuore pulsante
        FactoryLoop gameLoop = new FactoryLoop(gridManager);
        gameLoop.start(); // IL GIOCO PARTE QUI (indipendentemente dalla GUI)

        // ---------------------------------------------------------
        // FASE 4: SETUP PLAYER & SCENARIO
        // ---------------------------------------------------------
        UUID playerUuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        ensurePlayerExists(dbManager, playerUuid);
        ensurePlayerHasPlot(dbManager, playerUuid);

        System.out.println("📥 Caricamento Plot dal Database...");
        gridManager.loadPlotFromDB(playerUuid);
        Thread.sleep(500);

        setupScenario(gridManager, blockRegistry, playerUuid);

        // ---------------------------------------------------------
        // FASE 5: GESTIONE GUI vs HEADLESS (FIX PER VPS)
        // ---------------------------------------------------------

        // Controlliamo se siamo su un server senza monitor (VPS) o su un PC Desktop
        if (GraphicsEnvironment.isHeadless()) {
            // --- MODALITÀ SERVER (VPS) ---
            System.out.println("\n==================================================");
            System.out.println("👻 MODALITÀ HEADLESS ATTIVA (SERVER)");
            System.out.println("   Nessun monitor rilevato. GUI disabilitata.");
            System.out.println("   Il Core sta girando in background.");
            System.out.println("==================================================\n");

            // Qui non lanciamo SwingUtilities.invokeLater, quindi niente crash!

        } else {
            // --- MODALITÀ DESKTOP (PC LOCALE) ---
            System.out.println("🖥️ Monitor rilevato: Avvio Interfaccia Grafica di Debug...");

            SwingUtilities.invokeLater(() -> {
                new MatterWorksGUI(
                        gridManager,
                        blockRegistry,
                        playerUuid,
                        () -> {
                            System.out.println("💾 Manual Save Requested via GUI...");
                            saverService.autoSaveTask();
                        },
                        () -> {
                            PlayerProfile p = repository.loadPlayerProfile(playerUuid);
                            return (p != null) ? p.getMoney() : 0.0;
                        }
                );
            });
        }

        // ---------------------------------------------------------
        // FASE 6: LOOP DI MANTENIMENTO (Server Keep-Alive)
        // ---------------------------------------------------------
        System.out.println("--- 🟢 SISTEMA ONLINE ---");

        int ticks = 0;
        while (true) {
            Thread.sleep(1000);
            ticks++;

            // Ogni 10 secondi eseguiamo un autosave
            if (ticks % 10 == 0) {
                System.out.println("💾 AutoSave Triggered...");
                saverService.autoSaveTask();

                // Logghiamo lo stato per capire che il server è vivo anche senza GUI
                PlayerProfile p = repository.loadPlayerProfile(playerUuid);
                if (p != null) {
                    System.out.println("   [Server Status] Saldo Player: " + p.getMoney() + "$ | Uptime: " + ticks + "s");
                }
            }
        }
    }

    // --- HELPER METODI ---

    private static void setupScenario(GridManager gm, BlockRegistry reg, UUID owner) {
        GridPosition drillPos = new GridPosition(10, 64, 10);
        if (gm.isAreaClear(drillPos, reg.getDimensions("drill_mk1"))) {
            gm.placeMachine(owner, drillPos, "drill_mk1");
            updateOrientation(gm, drillPos, Direction.NORTH);
        }

        GridPosition beltPos = new GridPosition(10, 64, 9);
        if (gm.isAreaClear(beltPos, reg.getDimensions("conveyor_belt"))) {
            gm.placeMachine(owner, beltPos, "conveyor_belt");
            updateOrientation(gm, beltPos, Direction.NORTH);
        }

        GridPosition nexusPos = new GridPosition(10, 64, 6);
        if (gm.isAreaClear(nexusPos, reg.getDimensions("nexus_core"))) {
            gm.placeMachine(owner, nexusPos, "nexus_core");
            updateOrientation(gm, nexusPos, Direction.SOUTH);
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

    static class MockWorld implements IWorldAccess {
        @Override public void setBlock(GridPosition pos, String blockId) {}
        @Override public boolean isBlockSolid(GridPosition pos) { return true; }
        @Override public void createVisuals(GridPosition pos, String visualId) {}
        @Override public Vector3Int fetchExternalBlockDimensions(String blockId) { return Vector3Int.one(); }
    }
}