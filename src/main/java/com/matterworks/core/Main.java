package com.matterworks.core;

import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.domain.machines.BlockRegistry;
import com.matterworks.core.database.dao.MachineDefinitionDAO;
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
        System.out.println("🍄 MatterWorks Core Starting...");
        CoreConfig.load();

        String url = "jdbc:mariadb://dev.matterworks.org:3306/matterworks_core?allowPublicKeyRetrieval=true&useSSL=false";
        DatabaseManager dbManager = new DatabaseManager(url, "Noctino52", "Yy72s7mRnVs3");

        IWorldAccess world = new MockWorld();
        MachineDefinitionDAO defDao = new MachineDefinitionDAO(dbManager);
        BlockRegistry blockRegistry = new BlockRegistry(world, defDao);
        blockRegistry.loadFromDatabase();

        WorldIntegrityValidator validator = new WorldIntegrityValidator(dbManager, blockRegistry);
        if (!validator.validateWorldIntegrity()) {
            System.err.println("🚨 ATTENZIONE: Il mondo contiene collisioni!");
        }

        IRepository repository = new MariaDBAdapter(dbManager);
        GridManager gridManager = new GridManager(repository, world, blockRegistry);
        GridSaverService saverService = new GridSaverService(gridManager, repository);

        FactoryLoop gameLoop = new FactoryLoop(gridManager);
        gameLoop.start();

        UUID playerUuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        ensurePlayerExists(dbManager, playerUuid);

        gridManager.loadPlotFromDB(playerUuid);

        if (!GraphicsEnvironment.isHeadless()) {
            SwingUtilities.invokeLater(() -> {
                // CORRETTO: 5 argomenti invece di 6
                new MatterWorksGUI(
                        gridManager,
                        blockRegistry,
                        playerUuid,
                        () -> {
                            System.out.println("💾 Saving...");
                            saverService.autoSaveTask();
                        },
                        repository
                );
            });
        }

        while (true) {
            Thread.sleep(10000);
            saverService.autoSaveTask();
        }
    }

    private static void ensurePlayerExists(DatabaseManager db, UUID uuid) {
        com.matterworks.core.database.dao.PlayerDAO playerDao = new com.matterworks.core.database.dao.PlayerDAO(db);
        PlayerProfile p = playerDao.load(uuid);
        if (p == null) {
            p = new PlayerProfile(uuid);
            p.setUsername("Noctino_Dev");
            p.setMoney(1000.0);
            p.setRank(PlayerProfile.PlayerRank.ADMIN);
            playerDao.save(p);
        }
    }

    static class MockWorld implements IWorldAccess {
        @Override public void setBlock(com.matterworks.core.common.GridPosition pos, String id) {}
        @Override public boolean isBlockSolid(com.matterworks.core.common.GridPosition pos) { return true; }
        @Override public void createVisuals(com.matterworks.core.common.GridPosition pos, String id) {}
        @Override public com.matterworks.core.common.Vector3Int fetchExternalBlockDimensions(String id) { return com.matterworks.core.common.Vector3Int.one(); }
    }
}