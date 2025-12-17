package com.matterworks.core;

import com.matterworks.core.common.Direction;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.database.dao.MachineDefinitionDAO;
import com.matterworks.core.database.dao.PlayerDAO;
import com.matterworks.core.database.dao.PlotDAO;
import com.matterworks.core.domain.machines.BlockRegistry;
import com.matterworks.core.domain.machines.DrillMachine;
import com.matterworks.core.domain.machines.PlacedMachine;
import com.matterworks.core.domain.matter.MatterColor;
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

        CoreConfig.load();

        String url = "jdbc:mariadb://dev.matterworks.org:3306/matterworks_core?allowPublicKeyRetrieval=true&useSSL=false";
        DatabaseManager dbManager = new DatabaseManager(url, "Noctino52", "Yy72s7mRnVs3");

        IWorldAccess world = new MockWorld();

        MachineDefinitionDAO defDao = new MachineDefinitionDAO(dbManager);
        BlockRegistry blockRegistry = new BlockRegistry(world, defDao);
        blockRegistry.loadFromDatabase();

        WorldIntegrityValidator validator = new WorldIntegrityValidator(dbManager, blockRegistry);
        if (!validator.validateWorldIntegrity()) {
            System.err.println("🚨 ATTENZIONE: Il mondo contiene collisioni! Controlla il DB.");
            Thread.sleep(2000);
        }

        IRepository repository = new MariaDBAdapter(dbManager);
        GridManager gridManager = new GridManager(repository, world, blockRegistry);
        GridSaverService saverService = new GridSaverService(gridManager, repository);

        FactoryLoop gameLoop = new FactoryLoop(gridManager);
        gameLoop.start();

        UUID playerUuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        ensurePlayerExists(dbManager, playerUuid);
        ensurePlayerHasPlot(dbManager, playerUuid);

        System.out.println("📥 Caricamento Plot dal Database...");
        gridManager.loadPlotFromDB(playerUuid);
        Thread.sleep(500);


        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("👻 MODALITÀ HEADLESS ATTIVA (SERVER)");
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