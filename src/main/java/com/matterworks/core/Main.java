package com.matterworks.core;

import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.database.dao.MachineDefinitionDAO;
import com.matterworks.core.database.dao.PlayerDAO;
import com.matterworks.core.domain.machines.registry.BlockRegistry;
import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.managers.GridManager;
import com.matterworks.core.managers.WorldIntegrityValidator;
import com.matterworks.core.ports.IWorldAccess;
import com.matterworks.core.synchronization.FactoryLoop;
import com.matterworks.core.synchronization.GridSaverService;
import com.matterworks.core.ui.CoreConfig;
import com.matterworks.core.ui.MariaDBAdapter;
import com.matterworks.core.ui.swing.app.MatterWorksGUI;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Main {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("🍄 MatterWorks Core Starting...");
        CoreConfig.load();

        Runtime rt = Runtime.getRuntime();
        System.out.println("[JVM] maxMemory=" + (rt.maxMemory() / (1024 * 1024)) + "MB"
                + " total=" + (rt.totalMemory() / (1024 * 1024)) + "MB"
                + " free=" + (rt.freeMemory() / (1024 * 1024)) + "MB");

        LaunchOptions opt = LaunchOptions.parse(args);

        // DB init
        String url = "jdbc:mariadb://dev.matterworks.org:3306/matterworks_core?allowPublicKeyRetrieval=true&useSSL=false";
        DatabaseManager dbManager = new DatabaseManager(url, "Noctino52", "Yy72s7mRnVs3");

        // World access (mock for now)
        IWorldAccess world = new MockWorld();

        MachineDefinitionDAO defDao = new MachineDefinitionDAO(dbManager);
        BlockRegistry blockRegistry = new BlockRegistry(world, defDao);
        blockRegistry.loadFromDatabase();

        WorldIntegrityValidator validator = new WorldIntegrityValidator(dbManager, blockRegistry);
        if (!validator.validateWorldIntegrity()) {
            System.err.println("🚨 ATTENZIONE: Il mondo contiene collisioni!");
        }

        // MariaDB-only repository adapter
        MariaDBAdapter repository = new MariaDBAdapter(dbManager);

        GridManager gridManager = new GridManager(repository, world, blockRegistry);

        GridSaverService saverService = new GridSaverService(gridManager, repository);
        gridManager.setSaverService(saverService);

        // Dev player
        UUID playerUuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        ensurePlayerExists(dbManager, playerUuid);

        // Preload plots for all players (as before)
        List<PlayerProfile> players = repository.getAllPlayers();
        int errors = 0;

        for (PlayerProfile p : players) {
            try {
                UUID id = p.getPlayerId();

                if (repository.getPlotId(id) == null) {
                    repository.createPlot(id, 1, 0, 0);
                }

                gridManager.preloadPlotFromDB(id);
            } catch (Exception e) {
                errors++;
                e.printStackTrace();
            }
        }

        try {
            if (repository.getPlotId(playerUuid) == null) {
                repository.createPlot(playerUuid, 1, 0, 0);
            }
            gridManager.loadPlotFromDB(playerUuid);
        } catch (Exception e) {
            errors++;
            e.printStackTrace();
        }

        System.out.println("✅ Preload plots requested for " + players.size() + " players. Errors: " + errors);

        // Start tick loop
        FactoryLoop gameLoop = new FactoryLoop(gridManager);
        gameLoop.start();

        // Autosave scheduler (optional)
        ScheduledExecutorService autosaveScheduler = null;
        if (opt.autosaveEnabled && opt.autosaveSeconds > 0) {
            autosaveScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mw-autosave");
                t.setDaemon(true);
                return t;
            });

            ScheduledExecutorService finalAutosaveScheduler = autosaveScheduler;
            autosaveScheduler.scheduleWithFixedDelay(() -> {
                try {
                    saverService.autoSaveTask();
                } catch (Throwable t) {
                    System.err.println("⚠️ AutoSave error:");
                    t.printStackTrace();
                }
            }, opt.autosaveSeconds, opt.autosaveSeconds, TimeUnit.SECONDS);

            // Shutdown hook saves once more + closes scheduler + stops loop
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    System.out.println("💾 ShutdownHook: saving before exit...");
                    saverService.autoSaveTask();
                    System.out.println("✅ ShutdownHook: save done.");
                } catch (Throwable t) {
                    t.printStackTrace();
                } finally {
                    shutdownQuietly(finalAutosaveScheduler);
                    gameLoop.stop();
                }
            }, "mw-shutdown"));
        } else {
            // If autosave disabled, still stop the loop on shutdown
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    gameLoop.stop();
                } catch (Throwable ignored) {}
            }, "mw-shutdown-stop"));
        }

        // UI
        boolean canShowSwing = !GraphicsEnvironment.isHeadless();
        boolean uiEnabled = opt.uiEnabled && canShowSwing;

        System.out.println("[BOOT] uiEnabled=" + uiEnabled
                + " (opt.uiEnabled=" + opt.uiEnabled + ", headless=" + GraphicsEnvironment.isHeadless() + ")"
                + " autosaveEnabled=" + (opt.autosaveEnabled && opt.autosaveSeconds > 0)
                + " autosaveSeconds=" + opt.autosaveSeconds
                + " runSeconds=" + opt.runSeconds);

        if (uiEnabled) {
            SwingUtilities.invokeLater(() -> new MatterWorksGUI(
                    gridManager,
                    blockRegistry,
                    playerUuid,
                    () -> {
                        System.out.println("💾 Manual Save...");
                        saverService.autoSaveTask();
                    },
                    repository
            ));
        }

        // Duration / lifetime
        if (opt.runSeconds > 0) {
            // Timed run: useful for benchmarking without UI.
            Thread.sleep(opt.runSeconds * 1000L);

            // Graceful stop
            if (opt.autosaveEnabled && opt.autosaveSeconds > 0) {
                try {
                    saverService.autoSaveTask();
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }

            shutdownQuietly(autosaveScheduler);
            gameLoop.stop();
            System.out.println("✅ Timed run complete. Exiting.");
            return;
        }

        // Otherwise keep process alive (UI or server-like)
        // Avoid busy loop: sleep forever in long intervals.
        while (true) {
            Thread.sleep(60_000L);
        }
    }

    private static void shutdownQuietly(ScheduledExecutorService svc) {
        if (svc == null) return;
        try {
            svc.shutdown();
            if (!svc.awaitTermination(2, TimeUnit.SECONDS)) {
                svc.shutdownNow();
            }
        } catch (InterruptedException e) {
            svc.shutdownNow();
            Thread.currentThread().interrupt();
        } catch (Throwable ignored) {
        }
    }

    private static void ensurePlayerExists(DatabaseManager db, UUID uuid) {
        PlayerDAO playerDao = new PlayerDAO(db);
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
        @Override public com.matterworks.core.common.Vector3Int fetchExternalBlockDimensions(String id) {
            return com.matterworks.core.common.Vector3Int.one();
        }
    }

    /**
     * Minimal launch options parser.
     * Supported:
     *  --no-ui | --headless
     *  --seconds=120
     *  --no-autosave
     *  --autosave-seconds=10
     *
     * Also supports system property:
     *  -Dmw.ui=false
     */
    private static final class LaunchOptions {
        final boolean uiEnabled;
        final boolean autosaveEnabled;
        final int autosaveSeconds;
        final int runSeconds;

        private LaunchOptions(boolean uiEnabled, boolean autosaveEnabled, int autosaveSeconds, int runSeconds) {
            this.uiEnabled = uiEnabled;
            this.autosaveEnabled = autosaveEnabled;
            this.autosaveSeconds = autosaveSeconds;
            this.runSeconds = runSeconds;
        }

        static LaunchOptions parse(String[] args) {
            boolean uiEnabled = true;
            boolean autosaveEnabled = true;
            int autosaveSeconds = 10;
            int runSeconds = 0;

            // System property override
            String mwUi = System.getProperty("mw.ui");
            if (mwUi != null && mwUi.equalsIgnoreCase("false")) {
                uiEnabled = false;
            }

            if (args != null) {
                for (String a : args) {
                    if (a == null) continue;

                    if (a.equalsIgnoreCase("--no-ui") || a.equalsIgnoreCase("--headless")) {
                        uiEnabled = false;
                        continue;
                    }
                    if (a.equalsIgnoreCase("--no-autosave")) {
                        autosaveEnabled = false;
                        continue;
                    }
                    if (a.startsWith("--autosave-seconds=")) {
                        autosaveSeconds = parseIntSafe(a.substring("--autosave-seconds=".length()), autosaveSeconds);
                        continue;
                    }
                    if (a.startsWith("--seconds=")) {
                        runSeconds = parseIntSafe(a.substring("--seconds=".length()), runSeconds);
                    }
                }
            }

            if (autosaveSeconds <= 0) autosaveEnabled = false;
            if (runSeconds < 0) runSeconds = 0;

            return new LaunchOptions(uiEnabled, autosaveEnabled, autosaveSeconds, runSeconds);
        }

        private static int parseIntSafe(String s, int def) {
            try {
                return Integer.parseInt(s.trim());
            } catch (Exception e) {
                return def;
            }
        }
    }
}
