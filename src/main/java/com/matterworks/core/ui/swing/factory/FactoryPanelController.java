package com.matterworks.core.ui.swing.factory;

import com.google.gson.JsonObject;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.domain.machines.base.PlacedMachine;
import com.matterworks.core.domain.machines.inspection.MachineInspectionInfo;
import com.matterworks.core.domain.machines.inspection.MachineInspector;
import com.matterworks.core.domain.matter.MatterColor;
import com.matterworks.core.managers.GridManager;

import javax.swing.*;
import java.awt.*;
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

    // ✅ NEW: repaint periodico (decoupled dal mouse)
    private final Timer visualRepaintTimer;

    private volatile boolean disposed = false;

    private volatile Snapshot snapshot = new Snapshot(Map.of(), Map.of());
    private volatile long lastFingerprint = 0L;

    private final IdentityHashMap<PlacedMachine, JsonObject> metaCache = new IdentityHashMap<>();
    private final IdentityHashMap<PlacedMachine, Long> metaCacheNanos = new IdentityHashMap<>();

    // prima era 150ms; con repaint periodico conviene un TTL un po' più alto
    private static final long META_CACHE_TTL_NANOS = 500_000_000L; // 0.5s

    private volatile boolean repaintQueued = false;

    // ✅ scegli quanto spesso vuoi aggiornare visivamente (250ms = 4fps; 1000ms = 1fps)
    private static final int VISUAL_REPAINT_MS = 250;

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

        // ✅ heartbeat di repaint: la UI si aggiorna anche se la fingerprint non cambia
        visualRepaintTimer = new Timer(VISUAL_REPAINT_MS, e -> {
            if (!disposed && panel.isDisplayable()) {
                panel.repaint();
            }
        });
        visualRepaintTimer.setRepeats(true);
        visualRepaintTimer.start();

        hookInputListeners();

        // refresh snapshot “strutturale” (macchine piazzate / risorse statiche)
        refreshExec.scheduleWithFixedDelay(this::refreshCacheLoop, 0, 180, TimeUnit.MILLISECONDS);
    }

    void dispose() {
        disposed = true;

        try {
            if (visualRepaintTimer != null) visualRepaintTimer.stop();
        } catch (Throwable ignored) {}

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

    GridManager.PlotAreaInfo getPlotAreaInfo() {
        UUID u = state.playerUuid;
        if (u == null) return null;
        try {
            return gridManager.getPlotAreaInfo(u);
        } catch (Throwable t) {
            return null;
        }
    }

    void forceRefreshNow() {
        if (disposed) return;
        synchronized (metaCache) {
            metaCache.clear();
            metaCacheNanos.clear();
        }
        refreshOnce(true);
        repaintCoalesced();
    }


    private static String buildTooltipHtml(MachineInspectionInfo info, int tier) {
        String name = safe(info.machineName());
        int matter = info.totalMatterCount();
        int colors = info.totalColorCount();
        String st = (info.state() != null) ? info.state().name() : "FERMA";

        var outLines = (info.targetOutputLines() != null && !info.targetOutputLines().isEmpty())
                ? info.targetOutputLines()
                : info.outputLines();

        StringBuilder sb = new StringBuilder(256);
        sb.append("<html>");
        sb.append("<b>").append(esc(name)).append("</b><br/>");

        sb.append("Tier: ").append(tier).append("<br/>");
        sb.append("Matter: ").append(matter).append(" | Colori: ").append(colors).append("<br/>");
        sb.append("Stato: ").append(esc(st)).append("<br/>");

        sb.append("<br/><u>Input</u><br/>");
        appendLines(sb, info.inputLines());

        sb.append("<br/><u>Output</u><br/>");
        appendLines(sb, outLines);

        sb.append("</html>");
        return sb.toString();
    }


    private static void appendLines(StringBuilder sb, java.util.List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            sb.append("-<br/>");
            return;
        }
        for (String s : lines) sb.append(esc(s)).append("<br/>");
    }

    private static String safe(String s) {
        return (s == null || s.isBlank()) ? "Unknown" : s;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void hookInputListeners() {

        panel.addMouseMotionListener(new MouseMotionAdapter() {

            @Override public void mouseMoved(MouseEvent e) {
                if (disposed) return;
                if (state.isPanning()) return;

                boolean changed = updateHoverFromMouse(e.getX(), e.getY());
                if (changed) repaintCoalesced();
            }

            @Override public void mouseDragged(MouseEvent e) {
                if (disposed) return;

                if (state.isPanning()) {
                    state.updatePan(e.getX(), e.getY());
                    repaintCoalesced();
                }
            }
        });

        panel.addMouseListener(new MouseAdapter() {

            @Override public void mousePressed(MouseEvent e) {
                if (disposed) return;

                if (SwingUtilities.isMiddleMouseButton(e)) {
                    state.beginPan(e.getX(), e.getY());
                    panel.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    return;
                }

                handleMousePress(e);
            }

            @Override public void mouseReleased(MouseEvent e) {
                if (disposed) return;

                if (SwingUtilities.isMiddleMouseButton(e)) {
                    state.endPan();
                    panel.setCursor(Cursor.getDefaultCursor());

                    boolean changed = updateHoverFromMouse(e.getX(), e.getY());
                    if (changed) repaintCoalesced();
                }
            }
        });

        panel.addMouseWheelListener(e -> {
            if (disposed) return;
            state.applyWheelZoom(e.getWheelRotation());
            repaintCoalesced();
        });

        panel.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_R) {
                    state.rotate();
                    repaintCoalesced();
                }
                if (e.getKeyCode() == KeyEvent.VK_HOME) {
                    state.resetCamera();
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
        FactoryPanelState.Viewport vp = state.computeViewport(panel.getWidth(), panel.getHeight());
        int cell = vp.cellPx();
        int offX = vp.offX();
        int offY = vp.offY();

        int gx = Math.floorDiv(x - offX, cell);
        int gz = Math.floorDiv(y - offY, cell);

        if (gx >= 0 && gx < FactoryPanelState.GRID_SIZE && gz >= 0 && gz < FactoryPanelState.GRID_SIZE) {
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
                machines = (snap != null) ? snap : Map.of();

                Map<GridPosition, MatterColor> res = gridManager.getTerrainResources(u);
                resources = (res != null) ? new HashMap<>(res) : Map.of();
            }
        } catch (Throwable ex) {
            ex.printStackTrace();
            machines = Map.of();
            resources = Map.of();
        }

        long fp = fingerprint(machines, resources);
        if (!forceRepaint && fp == lastFingerprint) {
            // ✅ niente repaint qui: ci pensa il timer VISUAL_REPAINT_MS
            return;
        }

        lastFingerprint = fp;
        snapshot = new Snapshot(machines, resources);

        if (forceRepaint) repaintCoalesced();
        // altrimenti, ancora una volta: il timer fa repaint “fluido”
    }

    private long fingerprint(Map<GridPosition, PlacedMachine> machines, Map<GridPosition, MatterColor> resources) {
        long h = 1469598103934665603L;

        if (machines != null) {
            for (var e : machines.entrySet()) {
                GridPosition p = e.getKey();
                PlacedMachine m = e.getValue();
                if (p == null || m == null) continue;

                h ^= (p.x() * 73856093L) ^ (p.y() * 19349663L) ^ (p.z() * 83492791L);
                h *= 1099511628211L;

                String id = m.getTypeId();
                if (id != null) {
                    h ^= id.hashCode();
                    h *= 1099511628211L;
                }
            }
        }

        if (resources != null) {
            for (var e : resources.entrySet()) {
                GridPosition p = e.getKey();
                MatterColor c = e.getValue();
                if (p == null || c == null) continue;

                h ^= (p.x() * 1327L) ^ (p.z() * 7331L) ^ c.name().hashCode();
                h *= 1099511628211L;
            }
        }

        return h;
    }

    private void repaintCoalesced() {
        if (disposed) return;
        if (repaintQueued) return;

        repaintQueued = true;
        SwingUtilities.invokeLater(() -> {
            repaintQueued = false;
            if (!disposed) panel.repaint();
        });
    }

    String getInspectionTooltipHtml(int mouseX, int mouseY) {
        if (disposed) return null;

        GridPosition p = gridPosFromMouse(mouseX, mouseY);
        if (p == null) return null;

        Snapshot snap = snapshot;
        if (snap == null || snap.machines() == null) return null;

        PlacedMachine m = snap.machines().get(p);
        if (m == null) return null;

        MachineInspectionInfo info;
        try {
            info = MachineInspector.inspect(m);
        } catch (Throwable t) {
            return null;
        }

        if (info == null || !info.showInUi()) return null;

        int tier = 1;
        try {
            UUID owner = state.playerUuid;
            tier = gridManager.getUnlockedMachineTier(owner, m.getTypeId());
        } catch (Throwable ignored) {}

        return buildTooltipHtml(info, tier);
    }

}
