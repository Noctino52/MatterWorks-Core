package com.matterworks.core.managers;

import com.matterworks.core.common.Direction;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.domain.factory.MachineFactory;
import com.matterworks.core.domain.machines.base.PlacedMachine;
import com.matterworks.core.domain.machines.production.DrillMachine;
import com.matterworks.core.domain.machines.registry.BlockRegistry;
import com.matterworks.core.domain.machines.structure.StructuralBlock;
import com.matterworks.core.domain.matter.MatterColor;
import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.model.PlotObject;
import com.matterworks.core.model.PlotUnlockState;
import com.matterworks.core.ports.IWorldAccess;
import com.matterworks.core.ui.MariaDBAdapter;
import com.matterworks.core.ui.ServerConfig;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

final class GridWorldService {

    private final GridManager gridManager;
    private final MariaDBAdapter repository;
    private final IWorldAccess worldAdapter;
    private final BlockRegistry blockRegistry;
    private final TechManager techManager;
    private final ExecutorService ioExecutor;
    private final GridRuntimeState state;

    GridWorldService(
            GridManager gridManager,
            MariaDBAdapter repository,
            IWorldAccess worldAdapter,
            BlockRegistry blockRegistry,
            TechManager techManager,
            ExecutorService ioExecutor,
            GridRuntimeState state
    ) {
        this.gridManager = gridManager;
        this.repository = repository;
        this.worldAdapter = worldAdapter;
        this.blockRegistry = blockRegistry;
        this.techManager = techManager;
        this.ioExecutor = ioExecutor;
        this.state = state;
    }

    // ==========================================================
    // SNAPSHOTS (GUI)
    // ==========================================================
    Map<GridPosition, PlacedMachine> getSnapshot(UUID ownerId) {
        Map<GridPosition, PlacedMachine> g = state.playerGrids.get(ownerId);
        return g != null ? new HashMap<>(g) : Collections.emptyMap();
    }

    Map<GridPosition, PlacedMachine> getAllMachinesSnapshot() {
        Map<GridPosition, PlacedMachine> all = new HashMap<>();
        for (var map : state.playerGrids.values()) all.putAll(map);
        return all;
    }

    Map<GridPosition, MatterColor> getTerrainResources(UUID playerId) {
        return state.playerResources.getOrDefault(playerId, Collections.emptyMap());
    }

    // ==========================================================
    // PLOT AREA (unlock / bounds)
    // ==========================================================
    GridManager.PlotAreaInfo getPlotAreaInfo(UUID ownerId) {
        ServerConfig cfg = state.getServerConfig();
        PlotUnlockState st = state.plotUnlockCache.getOrDefault(ownerId, PlotUnlockState.zero());

        int startX = Math.max(1, cfg.plotStartingX());
        int startY = Math.max(1, cfg.plotStartingY());
        int maxX = Math.max(startX, cfg.plotMaxX());
        int maxY = Math.max(startY, cfg.plotMaxY());
        int incX = Math.max(1, cfg.plotIncreaseX());
        int incY = Math.max(1, cfg.plotIncreaseY());

        int extraX = Math.max(0, Math.min(st.extraX(), maxX - startX));
        int extraY = Math.max(0, Math.min(st.extraY(), maxY - startY));

        return new GridManager.PlotAreaInfo(startX, startY, maxX, maxY, incX, incY, extraX, extraY);
    }

    private boolean isCellWithinMax(UUID ownerId, int gx, int gz) {
        GridManager.PlotAreaInfo info = getPlotAreaInfo(ownerId);
        return gx >= 0 && gx < info.maxX() && gz >= 0 && gz < info.maxY();
    }

    private boolean isCellUnlocked(UUID ownerId, int gx, int gz) {
        GridManager.PlotAreaInfo info = getPlotAreaInfo(ownerId);
        return gx >= info.minX() && gx < info.maxXExclusive()
                && gz >= info.minZ() && gz < info.maxZExclusive();
    }

    private boolean isWithinPlotBounds(UUID ownerId, GridPosition origin, Vector3Int size) {
        if (origin == null || size == null) return false;
        GridManager.PlotAreaInfo info = getPlotAreaInfo(ownerId);

        int sx = Math.max(1, size.x());
        int sz = Math.max(1, size.z());

        int minX = origin.x();
        int minZ = origin.z();
        int maxXEx = origin.x() + sx;
        int maxZEx = origin.z() + sz;

        return minX >= 0 && minZ >= 0 && maxXEx <= info.maxX() && maxZEx <= info.maxY();
    }

    private boolean isAreaUnlocked(UUID ownerId, GridPosition origin, Vector3Int size) {
        if (origin == null || size == null) return false;

        int sx = Math.max(1, size.x());
        int sz = Math.max(1, size.z());

        for (int dx = 0; dx < sx; dx++) {
            for (int dz = 0; dz < sz; dz++) {
                int gx = origin.x() + dx;
                int gz = origin.z() + dz;
                if (!isCellWithinMax(ownerId, gx, gz)) return false;
                if (!isCellUnlocked(ownerId, gx, gz)) return false;
            }
        }
        return true;
    }

    boolean increasePlotUnlockedArea(UUID ownerId) {
        state.touchPlayer(ownerId);

        PlayerProfile p = state.getCachedProfile(ownerId);
        if (p == null || !p.isAdmin()) return false;

        GridManager.PlotAreaInfo info = getPlotAreaInfo(ownerId);
        PlotUnlockState cur = state.plotUnlockCache.getOrDefault(ownerId, PlotUnlockState.zero());

        int newExtraX = cur.extraX() + info.increaseX();
        int newExtraY = cur.extraY() + info.increaseY();

        int unlockedX = Math.min(info.maxX(), info.startingX() + newExtraX);
        int unlockedY = Math.min(info.maxY(), info.startingY() + newExtraY);

        PlotUnlockState next = new PlotUnlockState(
                Math.max(0, unlockedX - info.startingX()),
                Math.max(0, unlockedY - info.startingY())
        );

        if (!repository.updatePlotUnlockState(ownerId, next)) return false;
        state.plotUnlockCache.put(ownerId, next);
        return true;
    }

    boolean decreasePlotUnlockedArea(UUID ownerId) {
        state.touchPlayer(ownerId);

        PlayerProfile p = state.getCachedProfile(ownerId);
        if (p == null || !p.isAdmin()) return false;

        GridManager.PlotAreaInfo info = getPlotAreaInfo(ownerId);
        PlotUnlockState cur = state.plotUnlockCache.getOrDefault(ownerId, PlotUnlockState.zero());

        PlotUnlockState next = new PlotUnlockState(
                Math.max(0, cur.extraX() - info.increaseX()),
                Math.max(0, cur.extraY() - info.increaseY())
        );

        GridManager.PlotAreaInfo nextInfo = new GridManager.PlotAreaInfo(
                info.startingX(), info.startingY(),
                info.maxX(), info.maxY(),
                info.increaseX(), info.increaseY(),
                next.extraX(), next.extraY()
        );

        Map<GridPosition, PlacedMachine> grid = state.playerGrids.get(ownerId);
        if (grid != null && !grid.isEmpty()) {
            for (GridPosition cell : grid.keySet()) {
                if (cell == null) continue;
                int gx = cell.x();
                int gz = cell.z();
                boolean inside = gx >= nextInfo.minX() && gx < nextInfo.maxXExclusive()
                        && gz >= nextInfo.minZ() && gz < nextInfo.maxZExclusive();
                if (!inside) return false;
            }
        }

        if (!repository.updatePlotUnlockState(ownerId, next)) return false;
        state.plotUnlockCache.put(ownerId, next);
        return true;
    }

    // ==========================================================
    // LOADING PLOTS
    // ==========================================================
    void preloadPlotFromDB(UUID ownerId) {
        if (ownerId == null) return;

        if (state.playerGrids.containsKey(ownerId)) {
            state.touchPlayer(ownerId);
            return;
        }

        state.touchPlayer(ownerId);

        ioExecutor.submit(() -> {
            loadPlotSynchronously(ownerId);
            PlayerProfile p = repository.loadPlayerProfile(ownerId);
            if (p != null) state.activeProfileCache.put(ownerId, p);
        });
    }

    void loadPlotFromDB(UUID ownerId) {
        if (ownerId == null) return;

        if (state.playerGrids.containsKey(ownerId)) {
            state.touchPlayer(ownerId);
            return;
        }

        state.touchPlayer(ownerId);

        ioExecutor.submit(() -> {
            loadPlotSynchronously(ownerId);
            PlayerProfile p = repository.loadPlayerProfile(ownerId);
            if (p != null) state.activeProfileCache.put(ownerId, p);
        });
    }

    // package-private: viene chiamato anche da EconomyService (reset/prestige/instant)
    void loadPlotSynchronously(UUID ownerId) {
        state.playerGrids.remove(ownerId);
        state.playerResources.remove(ownerId);
        state.plotUnlockCache.remove(ownerId);
        state.sleepingPlayers.remove(ownerId);

        synchronized (state.tickingMachines) {
            state.tickingMachines.removeIf(m -> m != null && ownerId.equals(m.getOwnerId()));
        }

        try {
            try {
                PlotUnlockState st = repository.loadPlotUnlockState(ownerId);
                state.plotUnlockCache.put(ownerId, (st != null ? st : PlotUnlockState.zero()));
            } catch (Throwable t) {
                state.plotUnlockCache.put(ownerId, PlotUnlockState.zero());
            }

            Long pid = repository.getPlotId(ownerId);
            if (pid != null) {
                Map<GridPosition, MatterColor> res = repository.loadResources(pid);
                if (res.isEmpty()) generateDefaultResources(ownerId, repository, pid, res);
                state.playerResources.put(ownerId, res);
            }

            List<PlotObject> dtos = repository.loadPlotMachines(ownerId);
            for (PlotObject d : dtos) {
                PlacedMachine m = MachineFactory.createFromModel(d, ownerId);
                if (m == null) continue;

                m.setGridContext(gridManager);
                internalAddMachine(ownerId, m);
            }

            state.touchPlayer(ownerId);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ==========================================================
    // SIMULATION LOOP
    // ==========================================================
    void tick(long t) {
        state.sweepInactivePlayers(t);

        synchronized (state.tickingMachines) {
            for (PlacedMachine m : state.tickingMachines) {
                if (m == null) continue;
                m.tick(t);
            }
        }
    }

    // ==========================================================
    // PLACEMENT / REMOVAL
    // ==========================================================
    boolean placeStructure(UUID ownerId, GridPosition pos, String nativeBlockId) {
        state.touchPlayer(ownerId);

        if (!state.canPlaceAnotherItem(ownerId)) return false;

        if (!isWithinPlotBounds(ownerId, pos, Vector3Int.one())) return false;
        if (!isAreaUnlocked(ownerId, pos, Vector3Int.one())) return false;

        if (!isAreaClear(ownerId, pos, Vector3Int.one())) return false;

        StructuralBlock block = new StructuralBlock(ownerId, pos, nativeBlockId);
        internalAddMachine(ownerId, block);
        block.onPlace(worldAdapter);

        Long id = repository.createMachine(ownerId, block);
        if (id != null) block.setDbId(id);

        return true;
    }

    boolean placeMachine(UUID ownerId, GridPosition pos, String typeId, Direction orientation) {
        state.touchPlayer(ownerId);

        if (ownerId == null || pos == null || typeId == null) return false;

        if (!state.canPlaceAnotherItem(ownerId)) return false;

        PlayerProfile p = state.getCachedProfile(ownerId);
        if (p == null || !techManager.canBuyItem(p, typeId)) return false;

        Vector3Int dim = blockRegistry.getDimensions(typeId);
        Vector3Int effDim = (orientation == Direction.EAST || orientation == Direction.WEST)
                ? new Vector3Int(dim.z(), dim.y(), dim.x())
                : dim;

        if (!isWithinPlotBounds(ownerId, pos, effDim)) return false;
        if (!isAreaUnlocked(ownerId, pos, effDim)) return false;

        if (!isAreaClear(ownerId, pos, effDim)) return false;

        if (!(p.isAdmin())) {
            if (repository.getInventoryItemCount(ownerId, typeId) <= 0) return false;
            repository.modifyInventoryItem(ownerId, typeId, -1);
        }

        PlotObject dto = new PlotObject(null, null, pos.x(), pos.y(), pos.z(), typeId, null);
        PlacedMachine m = MachineFactory.createFromModel(dto, ownerId);
        if (m == null) {
            if (!p.isAdmin()) repository.modifyInventoryItem(ownerId, typeId, +1);
            return false;
        }

        m.setOrientation(orientation);

        // drill only on veins
        if (m instanceof DrillMachine drill) {
            Map<GridPosition, MatterColor> resMap = state.playerResources.get(ownerId);
            MatterColor resAt = (resMap != null) ? resMap.get(pos) : null;

            if (resAt == null) {
                if (!p.isAdmin()) repository.modifyInventoryItem(ownerId, typeId, +1);
                return false;
            }

            drill.setResourceToMine(resAt);
        }

        internalAddMachine(ownerId, m);
        m.onPlace(worldAdapter);

        Long id = repository.createMachine(ownerId, m);
        if (id != null) m.setDbId(id);

        repository.logTransaction(p, "PLACE_MACHINE", "NONE", 0, typeId);
        return true;
    }

    void removeComponent(UUID ownerId, GridPosition pos) {
        state.touchPlayer(ownerId);

        if (ownerId == null || pos == null) return;

        PlayerProfile p = state.getCachedProfile(ownerId);
        if (p == null) return;

        if (!p.isAdmin() && !isCellUnlocked(ownerId, pos.x(), pos.z())) return;

        PlacedMachine target = getMachineAt(ownerId, pos);
        if (target == null) return;

        if (!(target instanceof StructuralBlock)) {
            if (!p.isAdmin()) {
                repository.modifyInventoryItem(ownerId, target.getTypeId(), +1);
            }
        }

        internalRemoveMachine(ownerId, target);
        target.onRemove(worldAdapter);

        if (target.getDbId() != null) {
            repository.deleteMachine(target.getDbId());
        }

        repository.logTransaction(p, "REMOVE_MACHINE", "NONE", 0, target.getTypeId());
    }

    // ==========================================================
    // SAVE / UNLOAD
    // ==========================================================
    void saveAndUnloadSpecific(UUID ownerId) {
        if (ownerId == null) return;

        Map<GridPosition, PlacedMachine> snap = getSnapshot(ownerId);
        if (!snap.isEmpty()) {
            List<PlacedMachine> dirty = snap.values().stream()
                    .filter(Objects::nonNull)
                    .distinct()
                    .filter(PlacedMachine::isDirty)
                    .collect(Collectors.toList());

            if (!dirty.isEmpty()) {
                repository.updateMachinesMetadata(dirty);
                dirty.forEach(PlacedMachine::cleanDirty);
            }
        }

        state.playerGrids.remove(ownerId);
        state.playerResources.remove(ownerId);
        state.plotUnlockCache.remove(ownerId);
        state.activeProfileCache.remove(ownerId);
        state.lastActivityMs.remove(ownerId);
        state.sleepingPlayers.remove(ownerId);

        synchronized (state.tickingMachines) {
            state.tickingMachines.removeIf(m -> m != null && ownerId.equals(m.getOwnerId()));
        }
    }

    // ==========================================================
    // GRID INTERNALS
    // ==========================================================
    PlacedMachine getMachineAt(UUID ownerId, GridPosition pos) {
        if (ownerId == null || pos == null) return null;
        Map<GridPosition, PlacedMachine> g = state.playerGrids.get(ownerId);
        if (g == null) return null;
        return g.get(pos);
    }

    private void internalAddMachine(UUID ownerId, PlacedMachine m) {
        if (ownerId == null || m == null) return;

        m.setGridContext(gridManager);

        Map<GridPosition, PlacedMachine> grid = state.playerGrids.computeIfAbsent(ownerId, _k -> new ConcurrentHashMap<>());

        Vector3Int dim;
        try {
            dim = blockRegistry.getDimensions(m.getTypeId());
        } catch (Exception ex) {
            dim = Vector3Int.one();
        }

        Vector3Int effDim = dim;
        if (m.getOrientation() == Direction.EAST || m.getOrientation() == Direction.WEST) {
            effDim = new Vector3Int(dim.z(), dim.y(), dim.x());
        }

        for (int x = 0; x < effDim.x(); x++) {
            for (int y = 0; y < effDim.y(); y++) {
                for (int z = 0; z < effDim.z(); z++) {
                    grid.put(new GridPosition(m.getPos().x() + x, m.getPos().y() + y, m.getPos().z() + z), m);
                }
            }
        }

        if (!state.sleepingPlayers.contains(ownerId)) {
            synchronized (state.tickingMachines) {
                if (!state.tickingMachines.contains(m)) state.tickingMachines.add(m);
            }
        }
    }

    private void internalRemoveMachine(UUID ownerId, PlacedMachine m) {
        if (ownerId == null || m == null) return;

        Map<GridPosition, PlacedMachine> grid = state.playerGrids.get(ownerId);
        if (grid != null) grid.entrySet().removeIf(e -> e.getValue() == m);

        synchronized (state.tickingMachines) {
            state.tickingMachines.removeIf(x -> x == m);
        }
    }

    private boolean isAreaClear(UUID ownerId, GridPosition pos, Vector3Int size) {
        Map<GridPosition, PlacedMachine> grid = state.playerGrids.getOrDefault(ownerId, Collections.emptyMap());
        for (int x = 0; x < size.x(); x++) {
            for (int y = 0; y < size.y(); y++) {
                for (int z = 0; z < size.z(); z++) {
                    if (grid.containsKey(new GridPosition(pos.x() + x, pos.y() + y, pos.z() + z))) return false;
                }
            }
        }
        return true;
    }

    // ==========================================================
    // RESOURCES GENERATION (identica)
    // ==========================================================
    private void generateDefaultResources(UUID ownerId, MariaDBAdapter db, Long pid, Map<GridPosition, MatterColor> out) {
        state.reloadServerConfig();
        ServerConfig serverConfig = state.getServerConfig();

        GridManager.PlotAreaInfo info = getPlotAreaInfo(ownerId);
        int maxX = info.maxX();
        int maxY = info.maxY();
        int y = 0;

        int targetRaw = Math.max(serverConfig.veinRaw(), 1);
        int targetRed = Math.max(serverConfig.veinRed(), 1);
        int targetBlue = Math.max(serverConfig.veinBlue(), 1);
        int targetYellow = Math.max(serverConfig.veinYellow(), 1);

        int unlockedRaw = Math.min(2, targetRaw);
        int unlockedRed = Math.min(1, targetRed);
        int unlockedBlue = Math.min(1, targetBlue);
        int unlockedYellow = Math.min(1, targetYellow);

        spawnInUnlockedUntil(db, pid, out, MatterColor.RAW, unlockedRaw, y, info);
        spawnInUnlockedUntil(db, pid, out, MatterColor.RED, unlockedRed, y, info);
        spawnInUnlockedUntil(db, pid, out, MatterColor.BLUE, unlockedBlue, y, info);
        spawnInUnlockedUntil(db, pid, out, MatterColor.YELLOW, unlockedYellow, y, info);

        for (int i = countColor(out, MatterColor.RAW); i < targetRaw; i++) spawnVeinInLockedArea(db, pid, out, MatterColor.RAW, y, info, maxX, maxY);
        for (int i = countColor(out, MatterColor.RED); i < targetRed; i++) spawnVeinInLockedArea(db, pid, out, MatterColor.RED, y, info, maxX, maxY);
        for (int i = countColor(out, MatterColor.BLUE); i < targetBlue; i++) spawnVeinInLockedArea(db, pid, out, MatterColor.BLUE, y, info, maxX, maxY);
        for (int i = countColor(out, MatterColor.YELLOW); i < targetYellow; i++) spawnVeinInLockedArea(db, pid, out, MatterColor.YELLOW, y, info, maxX, maxY);
    }

    private void spawnInUnlockedUntil(MariaDBAdapter db, Long pid, Map<GridPosition, MatterColor> out,
                                      MatterColor t, int desiredUnlocked, int y, GridManager.PlotAreaInfo info) {
        if (desiredUnlocked <= 0) return;

        int minX = info.minX();
        int maxXEx = info.maxXExclusive();
        int minZ = info.minZ();
        int maxZEx = info.maxZExclusive();

        while (countColorInUnlocked(out, t, y, info) < desiredUnlocked) {
            int before = out.size();
            spawnVeinInBounds(db, pid, out, t, y, minX, maxXEx, minZ, maxZEx);
            if (out.size() == before) break;
        }
    }

    private int countColorInUnlocked(Map<GridPosition, MatterColor> out, MatterColor c, int y, GridManager.PlotAreaInfo info) {
        if (out == null || out.isEmpty() || c == null) return 0;

        int minX = info.minX();
        int maxXEx = info.maxXExclusive();
        int minZ = info.minZ();
        int maxZEx = info.maxZExclusive();

        int n = 0;
        for (var e : out.entrySet()) {
            GridPosition p = e.getKey();
            MatterColor t = e.getValue();
            if (p == null || t == null) continue;
            if (p.y() != y) continue;
            if (t != c) continue;

            if (p.x() >= minX && p.x() < maxXEx && p.z() >= minZ && p.z() < maxZEx) n++;
        }
        return n;
    }

    private void spawnVeinInLockedArea(MariaDBAdapter db, Long pid, Map<GridPosition, MatterColor> out,
                                       MatterColor t, int y, GridManager.PlotAreaInfo info, int maxX, int maxY) {
        if (out == null) return;

        int minX = info.minX();
        int maxXEx = info.maxXExclusive();
        int minZ = info.minZ();
        int maxZEx = info.maxZExclusive();

        Random r = new Random();

        for (int tries = 0; tries < 3000; tries++) {
            int x = r.nextInt(maxX);
            int z = r.nextInt(maxY);

            boolean insideUnlocked = x >= minX && x < maxXEx && z >= minZ && z < maxZEx;
            if (insideUnlocked) continue;

            GridPosition key = new GridPosition(x, y, z);
            if (out.containsKey(key)) continue;

            db.saveResource(pid, x, z, t);
            out.put(key, t);
            return;
        }

        for (int x = 0; x < maxX; x++) {
            for (int z = 0; z < maxY; z++) {
                boolean insideUnlocked = x >= minX && x < maxXEx && z >= minZ && z < maxZEx;
                if (insideUnlocked) continue;

                GridPosition key = new GridPosition(x, y, z);
                if (out.containsKey(key)) continue;

                db.saveResource(pid, x, z, t);
                out.put(key, t);
                return;
            }
        }
    }

    private int countColor(Map<GridPosition, MatterColor> out, MatterColor c) {
        if (out == null || out.isEmpty() || c == null) return 0;
        int n = 0;
        for (MatterColor t : out.values()) if (t == c) n++;
        return n;
    }

    private void spawnVeinInBounds(MariaDBAdapter db, Long pid, Map<GridPosition, MatterColor> out,
                                   MatterColor t, int y, int minX, int maxXEx, int minZ, int maxZEx) {
        if (out == null) return;

        Random r = new Random();

        for (int tries = 0; tries < 2000; tries++) {
            int x = r.nextInt(maxXEx - minX) + minX;
            int z = r.nextInt(maxZEx - minZ) + minZ;

            GridPosition key = new GridPosition(x, y, z);
            if (out.containsKey(key)) continue;

            db.saveResource(pid, x, z, t);
            out.put(key, t);
            return;
        }

        for (int x = minX; x < maxXEx; x++) {
            for (int z = minZ; z < maxZEx; z++) {
                GridPosition key = new GridPosition(x, y, z);
                if (out.containsKey(key)) continue;

                db.saveResource(pid, x, z, t);
                out.put(key, t);
                return;
            }
        }
    }
}
