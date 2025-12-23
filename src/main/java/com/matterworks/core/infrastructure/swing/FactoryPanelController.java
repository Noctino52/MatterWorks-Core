package com.matterworks.core.infrastructure.swing;

import com.google.gson.JsonObject;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.domain.machines.PlacedMachine;
import com.matterworks.core.domain.matter.MatterColor;
import com.matterworks.core.managers.GridManager;

import javax.swing.*;
import java.awt.event.*;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

final class FactoryPanelController {

    record Snapshot(Map<GridPosition, PlacedMachine> machines, Map<GridPosition, MatterColor> resources) {}

    private final FactoryPanel panel;
    private final GridManager gridManager;
    private final FactoryPanelState state;

    private final Runnable onEconomyMaybeChanged;

    private final ScheduledExecutorService refreshExec;
    private final ExecutorService commandExec;

    private volatile boolean disposed = false;

    private volatile Snapshot snapshot = new Snapshot(Map.of(), Map.of());
    private volatile long lastFingerprint = 0L;

    private final IdentityHashMap<PlacedMachine, JsonObject> metaCache = new IdentityHashMap<>();
    private final IdentityHashMap<PlacedMachine, Long> metaCacheNanos = new IdentityHashMap<>();
    private static final long META_CACHE_TTL_NANOS = 150_000_000L;

    private volatile boolean repaintQueued = false;

    FactoryPanelController(FactoryPanel panel,
                           GridManager gridManager,
                           FactoryPanelState state,
                           Runnable onEconomyMaybeChanged) {

        this.panel = panel;
        this.gridManager = gridManager;
        this.state = state;
        this.onEconomyMaybeChanged = onEconomyMaybeChanged;

        refreshExec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mw-factorypanel-refresh");
            t.setDaemon(true);
            return t;
        });

        commandExec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mw-factorypanel-commands");
            t.setDaemon(true);
            return t;
        });

        hookInputListeners();
        refreshExec.scheduleWithFixedDelay(this::refreshCacheLoop, 0, 180, TimeUnit.MILLISECONDS);
    }

    void dispose() {
        disposed = true;
        try { refreshExec.shutdownNow(); } catch (Throwable ignored) {}
        try { commandExec.shutdownNow(); } catch (Throwable ignored) {}

        snapshot = new Snapshot(Map.of(), Map.of());
        synchronized (metaCache) {
            metaCache.clear();
            metaCacheNanos.clear();
        }
    }

    Snapshot getSnapshot() {
        return snapshot;
    }

    JsonObject getMetaCached(PlacedMachine m) {
        if (m == null) return null;
        long now = System.nanoTime();
        synchronized (metaCache) {
            Long last = metaCacheNanos.get(m);
            if (last != null && (now - last) <= META_CACHE_TTL_NANOS) {
                return metaCache.get(m);
            }
            JsonObject meta;
            try { meta = m.serialize(); } catch (Throwable ex) { meta = null; }
            metaCache.put(m, meta);
            metaCacheNanos.put(m, now);
            return meta;
        }
    }

    void forceRefreshNow() {
        if (disposed) return;
        synchronized (metaCache) {
            metaCache.clear();
            metaCacheNanos.clear();
        }
        refreshOnce(true);
    }

    private void hookInputListeners() {
        panel.setFocusable(true);
        panel.setDoubleBuffered(true);

        panel.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                boolean changed = updateHoverFromMouse(e.getX(), e.getY());
                if (changed) repaintCoalesced();
            }
        });

        panel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                panel.requestFocusInWindow();
                handleMousePress(e);
            }
        });

        panel.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_R) {
                    state.rotate();
                    repaintCoalesced();
                }
            }
        });
    }

    private boolean updateHoverFromMouse(int x, int y) {
        GridPosition p = gridPosFromMouse(x, y);
        if (p != null) {
            if (p.x() == state.lastHoverGX && p.z() == state.lastHoverGZ &&
                    state.mouseHoverPos != null && state.mouseHoverPos.y() == state.currentLayer) {
                return false;
            }
            state.lastHoverGX = p.x();
            state.lastHoverGZ = p.z();
            state.mouseHoverPos = p;
            return true;
        }

        if (state.mouseHoverPos != null) {
            state.clearHover();
            return true;
        }
        return false;
    }

    private GridPosition gridPosFromMouse(int x, int y) {
        int gx = (x - FactoryPanelRenderer.OFFSET_X) / FactoryPanelRenderer.CELL_SIZE;
        int gz = (y - FactoryPanelRenderer.OFFSET_Y) / FactoryPanelRenderer.CELL_SIZE;
        if (gx >= 0 && gx < FactoryPanelRenderer.GRID_SIZE && gz >= 0 && gz < FactoryPanelRenderer.GRID_SIZE) {
            return new GridPosition(gx, state.currentLayer, gz);
        }
        return null;
    }

    private void handleMousePress(MouseEvent e) {
        if (disposed) return;

        UUID u = state.playerUuid;
        if (u == null) return;

        GridPosition target = gridPosFromMouse(e.getX(), e.getY());
        if (target == null) return;

        // feedback immediato
        state.mouseHoverPos = target;
        state.lastHoverGX = target.x();
        state.lastHoverGZ = target.z();
        repaintCoalesced();

        final String tool = state.currentTool;
        final var ori = state.currentOrientation;

        try {
            commandExec.submit(() -> {
                try {
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        if (tool != null && tool.startsWith("STRUCTURE:")) {
                            String nativeId = tool.substring(10);
                            gridManager.placeStructure(u, target, nativeId);
                        } else {
                            gridManager.placeMachine(u, target, tool, ori);
                        }
                    } else if (SwingUtilities.isRightMouseButton(e)) {
                        gridManager.removeComponent(u, target);
                    }
                } catch (Throwable ex) {
                    ex.printStackTrace();
                } finally {
                    forceRefreshNow();
                    if (onEconomyMaybeChanged != null) SwingUtilities.invokeLater(onEconomyMaybeChanged);
                }
            });
        } catch (RejectedExecutionException ignored) {}
    }

    private void refreshCacheLoop() {
        refreshOnce(false);
    }

    private void refreshOnce(boolean forceRepaint) {
        if (disposed) return;

        UUID u = state.playerUuid;

        Map<GridPosition, PlacedMachine> machines;
        Map<GridPosition, MatterColor> resources;

        try {
            if (u == null) {
                machines = Map.of();
                resources = Map.of();
            } else {
                Map<GridPosition, PlacedMachine> snap = gridManager.getSnapshot(u);
                machines = (snap == null || snap.isEmpty()) ? Map.of() : new HashMap<>(snap);

                if (state.currentLayer == 0) {
                    Map<GridPosition, MatterColor> res = gridManager.getTerrainResources(u);
                    resources = (res == null || res.isEmpty()) ? Map.of() : new HashMap<>(res);
                } else {
                    resources = Map.of();
                }
            }
        } catch (Throwable t) {
            machines = Map.of();
            resources = Map.of();
        }

        snapshot = new Snapshot(machines, resources);

        long fp = computeFingerprint(machines, resources, state.currentLayer);
        boolean changed = fp != lastFingerprint;
        lastFingerprint = fp;

        if (forceRepaint || changed) repaintCoalesced();
    }

    private long computeFingerprint(Map<GridPosition, PlacedMachine> machines, Map<GridPosition, MatterColor> resources, int layer) {
        long h = 1469598103934665603L;
        h = fnv64(h, layer);

        if (machines != null && !machines.isEmpty()) {
            for (Map.Entry<GridPosition, PlacedMachine> e : machines.entrySet()) {
                GridPosition p = e.getKey();
                PlacedMachine m = e.getValue();
                if (p == null || m == null) continue;

                h = fnv64(h, p.hashCode());
                Long id = m.getDbId();
                h = fnv64(h, id != null ? id.hashCode() : System.identityHashCode(m));

                String t = m.getTypeId();
                if (t != null) h = fnv64(h, t.hashCode());

                var d = m.getOrientation();
                if (d != null) h = fnv64(h, d.ordinal());

                h = fnv64(h, m.isDirty() ? 1 : 0);
            }
        }

        if (resources != null && !resources.isEmpty()) {
            for (Map.Entry<GridPosition, MatterColor> e : resources.entrySet()) {
                GridPosition p = e.getKey();
                MatterColor c = e.getValue();
                if (p == null || c == null) continue;
                h = fnv64(h, p.hashCode());
                h = fnv64(h, c.ordinal());
            }
        }

        return h;
    }

    private long fnv64(long h, int v) {
        h ^= (v & 0xFFFFFFFFL);
        h *= 1099511628211L;
        return h;
    }

    private void repaintCoalesced() {
        if (disposed) return;
        if (repaintQueued) return;
        repaintQueued = true;

        SwingUtilities.invokeLater(() -> {
            repaintQueued = false;
            if (!disposed) panel.repaint(0, 0, panel.getWidth(), panel.getHeight());
        });
    }
}
