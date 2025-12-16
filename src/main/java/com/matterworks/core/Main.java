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

        // 1. TRIVELLA a (10, 64, 10). Output NORD -> Z=9
        GridPosition drillPos = new GridPosition(10, 64, 10);
        if (gm.isAreaClear(drillPos, reg.getDimensions("drill_mk1"))) {
            gm.placeMachine(owner, drillPos, "drill_mk1");
            updateOrientation(gm, drillPos, Direction.NORTH);
        }

        // 2. NASTRO 1 a (10, 64, 9). Riceve da 10, Output NORD -> Z=8
        GridPosition beltPos1 = new GridPosition(10, 64, 9);
        if (gm.isAreaClear(beltPos1, reg.getDimensions("conveyor_belt"))) {
            gm.placeMachine(owner, beltPos1, "conveyor_belt");
            updateOrientation(gm, beltPos1, Direction.NORTH);
        }

        // 3. CHROMATOR a (10, 64, 8). Riceve da 9, Output NORD -> Z=7
        GridPosition chromatorPos = new GridPosition(10, 64, 8);
        if (gm.isAreaClear(chromatorPos, reg.getDimensions("chromator"))) {
            System.out.println("🆕 Piazzamento Chromator...");
            gm.placeMachine(owner, chromatorPos, "chromator");
            updateOrientation(gm, chromatorPos, Direction.NORTH);
        }

        // 4. NASTRO 2 (NUOVO) a (10, 64, 7). Riceve da 8, Output NORD -> Z=6
        GridPosition beltPos2 = new GridPosition(10, 64, 7);
        if (gm.isAreaClear(beltPos2, reg.getDimensions("conveyor_belt"))) {
            System.out.println("🆕 Piazzamento Nastro di Uscita...");
            gm.placeMachine(owner, beltPos2, "conveyor_belt");
            updateOrientation(gm, beltPos2, Direction.NORTH);
        }

        // 5. NEXUS CORE a (10, 64, 4).
        // Dimensioni 3x3x3. Occupa Z=4, Z=5, Z=6.
        // Il Nastro 2 (a Z=7) spinge dentro Z=6 (che è il retro del Nexus).
        GridPosition nexusPos = new GridPosition(10, 64, 4);
        if (gm.isAreaClear(nexusPos, reg.getDimensions("nexus_core"))) {
            System.out.println("🆕 Piazzamento Nexus Core...");
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