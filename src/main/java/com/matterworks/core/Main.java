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
import java.awt.GraphicsEnvironment;
import java.util.UUID;

public class Main {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("🏭 MatterWorks Core Starting...");

        // ---------------------------------------------------------
        // FASE 0: CONFIGURAZIONE & INFRASTRUTTURA
        // ---------------------------------------------------------
        CoreConfig.load();

        // ✅ URL AGGIORNATO (dev.matterworks.org)
        String url = "jdbc:mariadb://dev.matterworks.org:3306/matterworks_core?allowPublicKeyRetrieval=true&useSSL=false";
        DatabaseManager dbManager = new DatabaseManager(url, "Noctino52", "Yy72s7mRnVs3");

        IWorldAccess world = new MockWorld();

        // ---------------------------------------------------------
        // FASE 1: REGISTRY & DEFINIZIONI
        // ---------------------------------------------------------
        MachineDefinitionDAO defDao = new MachineDefinitionDAO(dbManager);
        BlockRegistry blockRegistry = new BlockRegistry(world, defDao);
        blockRegistry.loadFromDatabase();

        // ---------------------------------------------------------
        // FASE 2: VALIDAZIONE INTEGRITÀ
        // ---------------------------------------------------------
        WorldIntegrityValidator validator = new WorldIntegrityValidator(dbManager, blockRegistry);
        if (!validator.validateWorldIntegrity()) {
            System.err.println("🚨 ATTENZIONE: Il mondo contiene collisioni! Controlla il DB.");
            Thread.sleep(2000);
        }

        // ---------------------------------------------------------
        // FASE 3: WIRING DEL CORE
        // ---------------------------------------------------------
        IRepository repository = new MariaDBAdapter(dbManager);
        GridManager gridManager = new GridManager(repository, world, blockRegistry);
        GridSaverService saverService = new GridSaverService(gridManager, repository);

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
        Thread.sleep(500);

        // Configura la catena: Drill -> Belt -> Chromator -> Belt -> Nexus
        setupScenario(gridManager, blockRegistry, playerUuid);

        // ---------------------------------------------------------
        // FASE 5: GESTIONE GUI vs HEADLESS
        // ---------------------------------------------------------
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("\n==================================================");
            System.out.println("👻 MODALITÀ HEADLESS ATTIVA (SERVER)");
            System.out.println("   Core attivo in background.");
            System.out.println("==================================================\n");
        } else {
            System.out.println("🖥️ Monitor rilevato: Avvio GUI...");
            SwingUtilities.invokeLater(() -> {
                new MatterWorksGUI(
                        gridManager,
                        blockRegistry,
                        playerUuid,
                        () -> {
                            System.out.println("💾 Manual Save...");
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
        // FASE 6: LOOP DI MANTENIMENTO
        // ---------------------------------------------------------
        System.out.println("--- 🟢 SISTEMA ONLINE ---");
        int ticks = 0;
        while (true) {
            Thread.sleep(1000);
            ticks++;
            if (ticks % 10 == 0) {
                System.out.println("💾 AutoSave Triggered...");
                saverService.autoSaveTask();

                PlayerProfile p = repository.loadPlayerProfile(playerUuid);
                if (p != null) {
                    System.out.println("   [Status] Saldo: " + p.getMoney() + "$ | Uptime: " + ticks + "s");
                }
            }
        }
    }

    // --- SETUP SCENARIO AGGIORNATO ---
    private static void setupScenario(GridManager gm, BlockRegistry reg, UUID owner) {

        // 1. TRIVELLA a (10, 0, 10). Output NORD -> Z=9
        // NOTA: Y=0 perché ora è obbligatorio per le trivelle!
        // (10,0,10) è definito come vena RAW nel GridManager.
        GridPosition drillPos = new GridPosition(10, 0, 10);
        if (gm.placeMachine(owner, drillPos, "drill_mk1")) {
            updateOrientation(gm, drillPos, Direction.NORTH);
        } else {
            System.err.println("❌ Setup Scenario fallito: Impossibile piazzare Trivella (Controlla Y=0 e Risorse)");
        }

        // 2. NASTRO SALITA (Elevator) - Simulato con Conveyor per ora
        // Dato che la trivella è a Y=0 e spara a Z=9 (che è a Y=0),
        // per ora costruiamo tutto a terra (Y=0) per semplicità,
        // oppure dovremmo fare nastri inclinati. Facciamo tutto a Y=0.

        // NASTRO a (10, 0, 9). Riceve da 10, Output NORD -> Z=8
        GridPosition beltPos1 = new GridPosition(10, 0, 9);
        if (gm.placeMachine(owner, beltPos1, "conveyor_belt")) {
            updateOrientation(gm, beltPos1, Direction.NORTH);
        }

        // 3. CHROMATOR a (10, 0, 8). Riceve da 9, Output NORD -> Z=7
        GridPosition chromatorPos = new GridPosition(10, 0, 8);
        if (gm.placeMachine(owner, chromatorPos, "chromator")) {
            System.out.println("🆕 Piazzamento Chromator...");
            updateOrientation(gm, chromatorPos, Direction.NORTH);
        }

        // 4. NASTRO USCITA a (10, 0, 7). Riceve da 8, Output NORD -> Z=6
        GridPosition beltPos2 = new GridPosition(10, 0, 7);
        if (gm.placeMachine(owner, beltPos2, "conveyor_belt")) {
            updateOrientation(gm, beltPos2, Direction.NORTH);
        }

        // 5. NEXUS CORE a (10, 0, 4).
        // Y=0. Occupa Z=4,5,6. Retro Z=6.
        GridPosition nexusPos = new GridPosition(10, 0, 4);
        if (gm.placeMachine(owner, nexusPos, "nexus_core")) {
            System.out.println("🆕 Piazzamento Nexus Core...");
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