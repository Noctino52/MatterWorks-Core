package com.matterworks.core;

import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.database.dao.MachineDefinitionDAO;
import com.matterworks.core.domain.machines.BlockRegistry;
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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

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

        UUID devUuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        ensurePlayerExists(dbManager, devUuid);

        preloadAllPlots(repository, gridManager);

        ScheduledExecutorService autosaveScheduler = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("mw-autosave", false));
        autosaveScheduler.scheduleAtFixedRate(() -> {
            try {
                saverService.autoSaveTask();
            } catch (Throwable t) {
                System.err.println("🚨 AutoSave scheduler error:");
                t.printStackTrace();
            }
        }, 10, 10, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("🧯 Shutdown requested. Stopping autosave + flushing save...");

            try {
                autosaveScheduler.shutdown();
                autosaveScheduler.awaitTermination(3, TimeUnit.SECONDS);
            } catch (Throwable ignored) {
            }

            tryStopLoop(gameLoop);

            try {
                saverService.autoSaveTask();
                System.out.println("✅ Shutdown flush completed.");
            } catch (Throwable t) {
                System.err.println("🚨 Shutdown flush failed:");
                t.printStackTrace();
            }
        }, "mw-shutdown-hook"));

        if (!GraphicsEnvironment.isHeadless()) {
            SwingUtilities.invokeLater(() -> new MatterWorksGUI(
                    gridManager,
                    blockRegistry,
                    devUuid,
                    () -> {
                        System.out.println("💾 Saving...");
                        saverService.autoSaveTask();
                    },
                    repository
            ));
        }

        Thread.currentThread().join();
    }

    private static void tryStopLoop(FactoryLoop loop) {
        if (loop == null) return;

        String[] methods = new String[] { "shutdown", "stop", "stopLoop", "requestStop", "close", "dispose", "interrupt" };

        for (String mName : methods) {
            try {
                Method m = loop.getClass().getMethod(mName);
                m.invoke(loop);
                return;
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable t) {
                return;
            }
        }
    }

    private static void preloadAllPlots(IRepository repository, GridManager gridManager) {
        final List<PlayerProfile> players;
        try {
            players = repository.getAllPlayers();
        } catch (RuntimeException ex) {
            System.err.println("🚨 Impossibile caricare la lista players per il preload dei plot.");
            ex.printStackTrace();
            return;
        }

        if (players == null || players.isEmpty()) {
            System.out.println("ℹ️ Nessun player trovato: nessun plot da pre-caricare.");
            return;
        }

        int requested = 0;
        int errors = 0;

        for (PlayerProfile p : players) {
            if (p == null) continue;

            UUID uuid = extractPlayerUuid(p);
            if (uuid == null) continue;

            try {
                gridManager.loadPlotFromDB(uuid);
                requested++;
            } catch (RuntimeException ex) {
                errors++;
                System.err.println("🚨 Errore preload plot per player " + uuid);
                ex.printStackTrace();
            }
        }

        System.out.println("✅ Preload plots requested for " + requested + " players. Errors: " + errors);
    }

    private static UUID extractPlayerUuid(PlayerProfile p) {
        UUID uuid = tryUuidFromMethods(p);
        if (uuid != null) return uuid;

        uuid = tryUuidFromFields(p);
        if (uuid != null) return uuid;

        return null;
    }

    private static UUID tryUuidFromMethods(PlayerProfile p) {
        String[] candidates = new String[]{
                "getUuid", "getUUID", "uuid",
                "getId", "id",
                "getPlayerUuid", "playerUuid",
                "getUniqueId", "uniqueId",
                "getPlayerId", "playerId"
        };

        Class<?> cls = p.getClass();
        for (String name : candidates) {
            try {
                Method m = cls.getMethod(name);
                Object v = m.invoke(p);
                UUID parsed = coerceToUuid(v);
                if (parsed != null) return parsed;
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored) {
            }
        }

        try {
            Method[] methods = cls.getMethods();
            for (Method m : methods) {
                if (m.getParameterCount() != 0) continue;
                String n = m.getName();
                if (n == null) continue;

                String lower = n.toLowerCase();
                if (!lower.contains("uuid") && !lower.equals("id") && !lower.endsWith("id")) continue;

                Object v = m.invoke(p);
                UUID parsed = coerceToUuid(v);
                if (parsed != null) return parsed;
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private static UUID tryUuidFromFields(PlayerProfile p) {
        String[] candidates = new String[]{
                "uuid", "UUID",
                "playerUuid", "playerUUID",
                "id", "uniqueId", "uniqueID",
                "playerId"
        };

        Class<?> cls = p.getClass();

        for (String name : candidates) {
            try {
                Field f = cls.getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(p);
                UUID parsed = coerceToUuid(v);
                if (parsed != null) return parsed;
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable ignored) {
            }
        }

        try {
            Field[] fields = cls.getDeclaredFields();
            for (Field f : fields) {
                if (f == null) continue;
                String n = f.getName();
                if (n == null) continue;

                String lower = n.toLowerCase();
                if (!lower.contains("uuid") && !lower.equals("id") && !lower.endsWith("id")) continue;

                f.setAccessible(true);
                Object v = f.get(p);
                UUID parsed = coerceToUuid(v);
                if (parsed != null) return parsed;
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private static UUID coerceToUuid(Object v) {
        if (v == null) return null;

        if (v instanceof UUID u) return u;

        if (v instanceof String s) {
            try {
                return UUID.fromString(s);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }

        if (v instanceof byte[] bytes) {
            return bytesToUuid(bytes);
        }

        return null;
    }

    private static UUID bytesToUuid(byte[] bytes) {
        if (bytes == null || bytes.length != 16) return null;
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        long most = bb.getLong();
        long least = bb.getLong();
        return new UUID(most, least);
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

    static final class NamedThreadFactory implements ThreadFactory {
        private final String baseName;
        private final boolean daemon;
        private int idx = 0;

        NamedThreadFactory(String baseName, boolean daemon) {
            this.baseName = baseName;
            this.daemon = daemon;
        }

        @Override
        public synchronized Thread newThread(Runnable r) {
            Thread t = new Thread(r, baseName + "-" + (++idx));
            t.setDaemon(daemon);
            return t;
        }
    }
}
