package com.matterworks.core.infrastructure.swing;

import com.google.gson.JsonObject;
import com.matterworks.core.common.Direction;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.domain.machines.*;
import com.matterworks.core.domain.matter.MatterColor;
import com.matterworks.core.managers.GridManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class FactoryPanel extends JPanel {

    private static final int GRID_SIZE = 20;
    private static final int CELL_SIZE = 40;
    private static final int OFFSET_X = 50;
    private static final int OFFSET_Y = 50;

    private static final Font FONT_TINY = new Font("SansSerif", Font.PLAIN, 9);
    private static final Font FONT_SMALL = new Font("SansSerif", Font.PLAIN, 10);
    private static final Font FONT_SYMBOL = new Font("Monospaced", Font.BOLD, 20);

    private static final Color BG = new Color(30, 30, 30);
    private static final Color GRID_COLOR = new Color(50, 50, 50);
    private static final Color GHOST_BORDER = Color.WHITE;
    private static final Color ARROW_COLOR = Color.YELLOW;

    private final GridManager gridManager;
    private final BlockRegistry registry;

    private volatile UUID playerUuid;

    private volatile String currentTool = "drill_mk1";
    private volatile Direction currentOrientation = Direction.NORTH;
    private volatile int currentLayer = 0;

    private volatile GridPosition mouseHoverPos = null;
    private final Runnable onStateChange;

    private volatile BufferedImage gridImage;
    private volatile int gridImageW = -1;
    private volatile int gridImageH = -1;

    private volatile Map<GridPosition, PlacedMachine> cachedMachines = Map.of();
    private volatile Map<GridPosition, MatterColor> cachedResources = Map.of();

    private final IdentityHashMap<PlacedMachine, JsonObject> metaCache = new IdentityHashMap<>();
    private final IdentityHashMap<PlacedMachine, Long> metaCacheNanos = new IdentityHashMap<>();
    private static final long META_CACHE_TTL_NANOS = 150_000_000L;

    private volatile boolean disposed = false;

    private final ScheduledExecutorService refreshExec;
    private volatile long lastFingerprint = 0L;
    private volatile boolean repaintQueued = false;

    private volatile String cachedGhostTool = null;
    private volatile Direction cachedGhostOri = null;
    private volatile Vector3Int cachedGhostDim = null;

    private volatile int lastHoverGX = Integer.MIN_VALUE;
    private volatile int lastHoverGZ = Integer.MIN_VALUE;

    public FactoryPanel(GridManager gridManager, BlockRegistry registry, UUID playerUuid, Runnable onStateChange) {
        this.gridManager = gridManager;
        this.registry = registry;
        this.playerUuid = playerUuid;
        this.onStateChange = onStateChange;

        setBackground(BG);
        setFocusable(true);
        setDoubleBuffered(true);

        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                boolean changed = updateMousePos(e.getX(), e.getY());
                if (changed) repaintCoalesced();
            }
        });

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                requestFocusInWindow();
                handleMouseClick(e);
                forceRefreshNow();
            }
        });

        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_R) rotate();
            }
        });

        refreshExec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mw-factorypanel-refresh");
            t.setDaemon(true);
            return t;
        });

        // refresh frequente ma OFF-EDT: paint disegna SOLO cache
        refreshExec.scheduleWithFixedDelay(this::refreshCacheLoop, 0, 180, TimeUnit.MILLISECONDS);
    }

    public void dispose() {
        disposed = true;
        try { refreshExec.shutdownNow(); } catch (Throwable ignored) {}

        cachedMachines = Map.of();
        cachedResources = Map.of();

        synchronized (metaCache) {
            metaCache.clear();
            metaCacheNanos.clear();
        }

        gridImage = null;
        cachedGhostTool = null;
        cachedGhostOri = null;
        cachedGhostDim = null;
    }

    public void forceRefreshNow() {
        if (disposed) return;
        invalidateGhostCache();
        synchronized (metaCache) {
            metaCache.clear();
            metaCacheNanos.clear();
        }
        refreshOnce(true);
    }

    @Override
    public Dimension getPreferredSize() {
        int w = OFFSET_X + GRID_SIZE * CELL_SIZE + OFFSET_X;
        int h = OFFSET_Y + GRID_SIZE * CELL_SIZE + OFFSET_Y;
        return new Dimension(w, h);
    }

    public void setPlayerUuid(UUID uuid) {
        this.playerUuid = uuid;
        mouseHoverPos = null;
        lastHoverGX = Integer.MIN_VALUE;
        lastHoverGZ = Integer.MIN_VALUE;
        forceRefreshNow();
    }

    public void setLayer(int y) {
        this.currentLayer = y;
        mouseHoverPos = null;
        lastHoverGX = Integer.MIN_VALUE;
        lastHoverGZ = Integer.MIN_VALUE;
        forceRefreshNow();
    }

    public int getCurrentLayer() {
        return currentLayer;
    }

    public void setTool(String toolId) {
        this.currentTool = toolId;
        invalidateGhostCache();
        repaintCoalesced();
    }

    public String getCurrentToolName() {
        return currentTool != null ? currentTool : "None";
    }

    public void rotate() {
        switch (currentOrientation) {
            case NORTH -> currentOrientation = Direction.EAST;
            case EAST -> currentOrientation = Direction.SOUTH;
            case SOUTH -> currentOrientation = Direction.WEST;
            case WEST -> currentOrientation = Direction.NORTH;
        }
        invalidateGhostCache();
        if (onStateChange != null) onStateChange.run();
        repaintCoalesced();
    }

    public String getCurrentOrientationName() {
        return currentOrientation.name();
    }

    private void invalidateGhostCache() {
        cachedGhostTool = null;
        cachedGhostOri = null;
        cachedGhostDim = null;
    }

    private boolean updateMousePos(int x, int y) {
        int gx = (x - OFFSET_X) / CELL_SIZE;
        int gz = (y - OFFSET_Y) / CELL_SIZE;

        if (gx >= 0 && gx < GRID_SIZE && gz >= 0 && gz < GRID_SIZE) {
            if (gx == lastHoverGX && gz == lastHoverGZ && mouseHoverPos != null && mouseHoverPos.y() == currentLayer) {
                return false;
            }
            lastHoverGX = gx;
            lastHoverGZ = gz;
            mouseHoverPos = new GridPosition(gx, currentLayer, gz);
            return true;
        }

        if (mouseHoverPos != null) {
            mouseHoverPos = null;
            lastHoverGX = Integer.MIN_VALUE;
            lastHoverGZ = Integer.MIN_VALUE;
            return true;
        }

        return false;
    }

    private void handleMouseClick(MouseEvent e) {
        if (disposed) return;
        UUID u = playerUuid;
        if (u == null) return;
        if (mouseHoverPos == null) return;

        if (SwingUtilities.isLeftMouseButton(e)) {
            if (currentTool != null && currentTool.startsWith("STRUCTURE:")) {
                String nativeId = currentTool.substring(10);
                gridManager.placeStructure(u, mouseHoverPos, nativeId);
            } else {
                gridManager.placeMachine(u, mouseHoverPos, currentTool, currentOrientation);
            }
        } else if (SwingUtilities.isRightMouseButton(e)) {
            gridManager.removeComponent(u, mouseHoverPos);
        }
    }

    private void refreshCacheLoop() {
        refreshOnce(false);
    }

    private void refreshOnce(boolean forceRepaint) {
        if (disposed) return;

        UUID u = playerUuid;
        Map<GridPosition, PlacedMachine> machines;
        Map<GridPosition, MatterColor> resources;

        try {
            if (u == null) {
                machines = Map.of();
                resources = Map.of();
            } else {
                Map<GridPosition, PlacedMachine> snap = gridManager.getSnapshot(u);
                machines = (snap == null || snap.isEmpty()) ? Map.of() : new HashMap<>(snap);

                if (currentLayer == 0) {
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

        cachedMachines = machines;
        cachedResources = resources;

        long fp = computeFingerprint(machines, resources, currentLayer);
        boolean changed = fp != lastFingerprint;
        lastFingerprint = fp;

        if (forceRepaint || changed) repaintCoalesced();
    }

    private long computeFingerprint(Map<GridPosition, PlacedMachine> machines, Map<GridPosition, MatterColor> resources, int layer) {
        long h = 1469598103934665603L; // FNV offset
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

                Direction d = m.getOrientation();
                if (d != null) h = fnv64(h, d.ordinal());

                Vector3Int dim = m.getDimensions();
                if (dim != null) {
                    h = fnv64(h, dim.x());
                    h = fnv64(h, dim.y());
                    h = fnv64(h, dim.z());
                }

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
            if (!disposed) repaint(0, 0, getWidth(), getHeight());
        });
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        ensureGridImage();

        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);

        if (currentLayer == 0) drawTerrainResourcesCached(g2);

        BufferedImage img = gridImage;
        if (img != null) g2.drawImage(img, 0, 0, null);

        drawMachinesCached(g2);
        drawGhost(g2);
    }

    private void ensureGridImage() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        BufferedImage img = gridImage;
        if (img != null && gridImageW == w && gridImageH == h) return;

        BufferedImage newImg = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gg = newImg.createGraphics();
        try {
            gg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            gg.setColor(GRID_COLOR);

            int x0 = OFFSET_X;
            int y0 = OFFSET_Y;
            int x1 = OFFSET_X + GRID_SIZE * CELL_SIZE;
            int y1 = OFFSET_Y + GRID_SIZE * CELL_SIZE;

            for (int i = 0; i <= GRID_SIZE; i++) {
                int x = x0 + i * CELL_SIZE;
                gg.drawLine(x, y0, x, y1);

                int y = y0 + i * CELL_SIZE;
                gg.drawLine(x0, y, x1, y);
            }
        } finally {
            gg.dispose();
        }

        gridImage = newImg;
        gridImageW = w;
        gridImageH = h;
    }

    private void drawTerrainResourcesCached(Graphics2D g) {
        Map<GridPosition, MatterColor> resources = cachedResources;
        if (resources == null || resources.isEmpty()) return;

        g.setFont(FONT_TINY);

        for (Map.Entry<GridPosition, MatterColor> entry : resources.entrySet()) {
            GridPosition pos = entry.getKey();
            MatterColor type = entry.getValue();
            if (pos == null || type == null) continue;
            if (pos.x() < 0 || pos.x() >= GRID_SIZE || pos.z() < 0 || pos.z() >= GRID_SIZE) continue;

            int x = OFFSET_X + (pos.x() * CELL_SIZE);
            int z = OFFSET_Y + (pos.z() * CELL_SIZE);

            g.setColor(getColorFromStr(type.name(), 100));
            g.fillRect(x, z, CELL_SIZE, CELL_SIZE);

            g.setColor(Color.WHITE);
            g.drawString(type.name(), x + 2, z + 12);
        }
    }

    private void drawMachinesCached(Graphics2D g) {
        Map<GridPosition, PlacedMachine> machines = cachedMachines;
        if (machines == null || machines.isEmpty()) return;

        IdentityHashMap<PlacedMachine, Boolean> seen = new IdentityHashMap<>();
        for (PlacedMachine m : machines.values()) {
            if (m == null) continue;
            if (seen.put(m, Boolean.TRUE) != null) continue;

            int baseY = m.getPos().y();
            int ySize = m.getDimensions().y();
            if (currentLayer < baseY || currentLayer >= baseY + ySize) continue;

            drawSingleMachine(g, m);
        }
    }

    private void drawSingleMachine(Graphics2D g, PlacedMachine m) {
        GridPosition p = m.getPos();
        int x = OFFSET_X + (p.x() * CELL_SIZE);
        int z = OFFSET_Y + (p.z() * CELL_SIZE);

        Vector3Int dims = m.getDimensions();
        int w = dims.x() * CELL_SIZE;
        int h = dims.z() * CELL_SIZE;

        if (m.getOrientation() == Direction.EAST || m.getOrientation() == Direction.WEST) {
            w = dims.z() * CELL_SIZE;
            h = dims.x() * CELL_SIZE;
        }

        if ("STRUCTURE_GENERIC".equals(m.getTypeId())) {
            g.setColor(new Color(80, 80, 80));
            g.fillRect(x + 2, z + 2, Math.max(0, w - 4), Math.max(0, h - 4));

            g.setColor(Color.WHITE);
            g.setFont(FONT_SMALL);

            String nativeId = "Block";
            JsonObject meta = getMetaCached(m);
            if (meta != null && meta.has("native_id")) {
                nativeId = meta.get("native_id").getAsString();
                if (nativeId != null && nativeId.contains(":")) {
                    String[] parts = nativeId.split(":", 2);
                    nativeId = parts.length == 2 ? parts[1] : nativeId;
                }
            }
            g.drawString(nativeId, x + 5, z + 25);
            return;
        }

        g.setColor(getColorForType(m.getTypeId()));
        g.fillRect(x + 2, z + 2, Math.max(0, w - 4), Math.max(0, h - 4));

        String type = m.getTypeId();
        if ("nexus_core".equals(type)) {
            drawNexusPorts(g, x, z, w, h);
        } else if ("splitter".equals(type)) {
            drawSplitterPorts(g, m, x, z, w, h);
        } else if ("merger".equals(type)) {
            drawMergerPorts(g, m, x, z, w, h);
        } else if ("lift".equals(type) || "dropper".equals(type)) {
            drawVerticalPorts(g, m, x, z);
        } else {
            drawStandardPorts(g, m, x, z, w, h);
        }

        if (m instanceof ConveyorBelt belt) drawBeltItem(g, belt, x, z);
        if (m instanceof Splitter splitter) drawSplitterItem(g, splitter, x, z);
        if (m instanceof Merger merger) drawMergerItem(g, merger, x, z);

        drawDirectionArrow(g, x, z, w, h, m.getOrientation());
    }

    private JsonObject getMetaCached(PlacedMachine m) {
        long now = System.nanoTime();
        synchronized (metaCache) {
            Long last = metaCacheNanos.get(m);
            if (last != null && (now - last) <= META_CACHE_TTL_NANOS) {
                return metaCache.get(m);
            }
            JsonObject meta;
            try {
                meta = m.serialize();
            } catch (Throwable ex) {
                meta = null;
            }
            metaCache.put(m, meta);
            metaCacheNanos.put(m, now);
            return meta;
        }
    }

    private void drawVerticalPorts(Graphics2D g, PlacedMachine m, int x, int z) {
        int p = 8;
        int c = CELL_SIZE;
        int relativeY = currentLayer - m.getPos().y();
        boolean isLift = "lift".equals(m.getTypeId());
        Point front = null, back = null;

        switch (m.getOrientation()) {
            case NORTH -> { front = new Point(x + c / 2 - p / 2, z); back = new Point(x + c / 2 - p / 2, z + c - p); }
            case SOUTH -> { front = new Point(x + c / 2 - p / 2, z + c - p); back = new Point(x + c / 2 - p / 2, z); }
            case EAST -> { front = new Point(x + c - p, z + c / 2 - p / 2); back = new Point(x, z + c / 2 - p / 2); }
            case WEST -> { front = new Point(x, z + c / 2 - p / 2); back = new Point(x + c - p, z + c / 2 - p / 2); }
        }

        if (isLift) {
            if (relativeY == 0) {
                if (back != null) drawPort(g, back, Color.BLUE);
                drawSymbol(g, x, z, "^", Color.WHITE);
            } else {
                if (front != null) drawPort(g, front, Color.GREEN);
                drawSymbol(g, x, z, "OUT", Color.YELLOW);
            }
        } else {
            if (relativeY == 1) {
                if (back != null) drawPort(g, back, Color.BLUE);
                drawSymbol(g, x, z, "v", Color.WHITE);
            } else {
                if (front != null) drawPort(g, front, Color.GREEN);
                drawSymbol(g, x, z, "OUT", Color.YELLOW);
            }
        }
    }

    private void drawSymbol(Graphics2D g, int x, int y, String sym, Color c) {
        g.setColor(c);
        g.setFont(FONT_SYMBOL);
        g.drawString(sym, x + 8, y + 26);
    }

    private void drawNexusPorts(Graphics2D g, int x, int z, int w, int h) {
        int p = 10;
        int cx = x + w / 2 - p / 2;
        int cz = z + h / 2 - p / 2;

        drawPort(g, new Point(cx, z), Color.BLUE);
        drawPort(g, new Point(cx, z + h - p), Color.GREEN);
        drawPort(g, new Point(x, cz), Color.BLUE);
        drawPort(g, new Point(x + w - p, cz), Color.GREEN);
    }

    private void drawSplitterPorts(Graphics2D g, PlacedMachine m, int x, int z, int w, int h) {
        int p = 8;
        int cx = x + w / 2 - p / 2;
        int cz = z + h / 2 - p / 2;

        switch (m.getOrientation()) {
            case NORTH -> { drawPort(g, new Point(cx, z + h - p), Color.BLUE); drawPort(g, new Point(x, cz), Color.GREEN); drawPort(g, new Point(x + w - p, cz), Color.GREEN); }
            case SOUTH -> { drawPort(g, new Point(cx, z), Color.BLUE); drawPort(g, new Point(x, cz), Color.GREEN); drawPort(g, new Point(x + w - p, cz), Color.GREEN); }
            case EAST -> { drawPort(g, new Point(x, cz), Color.BLUE); drawPort(g, new Point(cx, z), Color.GREEN); drawPort(g, new Point(cx, z + h - p), Color.GREEN); }
            case WEST -> { drawPort(g, new Point(x + w - p, cz), Color.BLUE); drawPort(g, new Point(cx, z), Color.GREEN); drawPort(g, new Point(cx, z + h - p), Color.GREEN); }
        }
    }

    private void drawMergerPorts(Graphics2D g, PlacedMachine m, int x, int z, int w, int h) {
        int p = 8;
        int cx = x + w / 2 - p / 2;
        int cz = z + h / 2 - p / 2;

        switch (m.getOrientation()) {
            case NORTH -> { drawPort(g, new Point(x, cz), Color.BLUE); drawPort(g, new Point(x + w - p, cz), Color.BLUE); drawPort(g, new Point(cx, z), Color.GREEN); }
            case SOUTH -> { drawPort(g, new Point(x, cz), Color.BLUE); drawPort(g, new Point(x + w - p, cz), Color.BLUE); drawPort(g, new Point(cx, z + h - p), Color.GREEN); }
            case EAST -> { drawPort(g, new Point(cx, z), Color.BLUE); drawPort(g, new Point(cx, z + h - p), Color.BLUE); drawPort(g, new Point(x + w - p, cz), Color.GREEN); }
            case WEST -> { drawPort(g, new Point(cx, z), Color.BLUE); drawPort(g, new Point(cx, z + h - p), Color.BLUE); drawPort(g, new Point(x, cz), Color.GREEN); }
        }
    }

    private void drawStandardPorts(Graphics2D g, PlacedMachine m, int x, int z, int w, int h) {
        int p = 8;
        Point front = null, back = null;

        switch (m.getOrientation()) {
            case NORTH -> { front = new Point(x + w / 2 - p / 2, z); back = new Point(x + w / 2 - p / 2, z + h - p); }
            case SOUTH -> { front = new Point(x + w / 2 - p / 2, z + h - p); back = new Point(x + w / 2 - p / 2, z); }
            case EAST -> { front = new Point(x + w - p, z + h / 2 - p / 2); back = new Point(x, z + h / 2 - p / 2); }
            case WEST -> { front = new Point(x, z + h / 2 - p / 2); back = new Point(x + w - p, z + h / 2 - p / 2); }
        }

        if (back != null) drawPort(g, back, Color.BLUE);
        if (front != null) drawPort(g, front, Color.GREEN);
    }

    private void drawPort(Graphics2D g, Point p, Color c) {
        g.setColor(c);
        g.fillRect(p.x, p.y, 8, 8);
        g.setColor(Color.BLACK);
        g.drawRect(p.x, p.y, 8, 8);
    }

    private void drawBeltItem(Graphics2D g, ConveyorBelt belt, int x, int z) {
        JsonObject meta = getMetaCached(belt);
        if (meta == null) return;
        if (!meta.has("currentItem")) return;
        if (!meta.get("currentItem").isJsonObject()) return;
        drawItemShape(g, meta.getAsJsonObject("currentItem"), x, z);
    }

    private void drawSplitterItem(Graphics2D g, Splitter splitter, int x, int z) {
        JsonObject meta = getMetaCached(splitter);
        if (meta == null) return;
        if (meta.has("items") && meta.getAsJsonArray("items").size() > 0) {
            var item = meta.getAsJsonArray("items").get(0);
            if (!item.isJsonNull() && item.isJsonObject()) drawItemShape(g, item.getAsJsonObject(), x, z);
        }
    }

    private void drawMergerItem(Graphics2D g, Merger merger, int x, int z) {
        JsonObject meta = getMetaCached(merger);
        if (meta == null) return;
        if (meta.has("items") && meta.getAsJsonArray("items").size() > 0) {
            var item = meta.getAsJsonArray("items").get(0);
            if (!item.isJsonNull() && item.isJsonObject()) drawItemShape(g, item.getAsJsonObject(), x, z);
        }
    }

    private void drawItemShape(Graphics2D g, JsonObject item, int x, int z) {
        String colorStr = item.has("color") ? item.get("color").getAsString() : "RAW";
        String shapeStr = (item.has("shape") && !item.get("shape").isJsonNull())
                ? item.get("shape").getAsString()
                : "LIQUID";

        g.setColor(getColorFromStr(colorStr, 255));
        int size = 18;
        int pad = (CELL_SIZE - size) / 2;

        if ("CUBE".equals(shapeStr)) {
            g.fillRect(x + pad, z + pad, size, size);
        } else {
            g.fillOval(x + pad, z + pad, size, size);
        }

        g.setColor(Color.WHITE);
        g.drawRect(x + pad, z + pad, size, size);
    }

    private void drawGhost(Graphics2D g) {
        GridPosition hover = mouseHoverPos;
        String tool = currentTool;
        if (hover == null || tool == null) return;

        Vector3Int dim = getGhostDimsCached();
        int dimX = dim.x();
        int dimZ = dim.z();

        int x = OFFSET_X + (hover.x() * CELL_SIZE);
        int z = OFFSET_Y + (hover.z() * CELL_SIZE);

        Color fill = tool.startsWith("STRUCTURE:") ? Color.LIGHT_GRAY : getColorForType(tool);

        Composite old = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f));
        g.setColor(fill);
        g.fillRect(x, z, dimX * CELL_SIZE, dimZ * CELL_SIZE);
        g.setComposite(old);

        g.setColor(GHOST_BORDER);
        g.drawRect(x, z, dimX * CELL_SIZE, dimZ * CELL_SIZE);

        drawDirectionArrow(g, x, z, dimX * CELL_SIZE, dimZ * CELL_SIZE, currentOrientation);
    }

    private Vector3Int getGhostDimsCached() {
        String tool = currentTool;
        if (tool == null) return Vector3Int.one();

        if (cachedGhostDim != null && tool.equals(cachedGhostTool) && currentOrientation == cachedGhostOri) {
            return cachedGhostDim;
        }

        Vector3Int base;
        if (tool.startsWith("STRUCTURE:")) {
            base = new Vector3Int(1, 1, 1);
        } else {
            base = registry.getDimensions(tool);
            if (base == null) base = Vector3Int.one();
        }

        int dimX = base.x();
        int dimZ = base.z();
        if (currentOrientation == Direction.EAST || currentOrientation == Direction.WEST) {
            dimX = base.z();
            dimZ = base.x();
        }

        cachedGhostTool = tool;
        cachedGhostOri = currentOrientation;
        cachedGhostDim = new Vector3Int(dimX, base.y(), dimZ);
        return cachedGhostDim;
    }

    private void drawDirectionArrow(Graphics2D g, int x, int y, int w, int h, Direction dir) {
        g.setColor(ARROW_COLOR);
        int cx = x + w / 2;
        int cy = y + h / 2;
        switch (dir) {
            case NORTH -> g.fillOval(cx - 3, y + 2, 6, 6);
            case SOUTH -> g.fillOval(cx - 3, y + h - 8, 6, 6);
            case EAST -> g.fillOval(x + w - 8, cy - 3, 6, 6);
            case WEST -> g.fillOval(x + 2, cy - 3, 6, 6);
        }
    }

    private Color getColorFromStr(String c, int alpha) {
        if (c == null) return new Color(120, 120, 120, alpha);
        return switch (c) {
            case "RED" -> new Color(200, 0, 0, alpha);
            case "BLUE" -> new Color(0, 0, 200, alpha);
            case "YELLOW" -> new Color(200, 200, 0, alpha);
            case "PURPLE" -> new Color(160, 32, 240, alpha);
            case "GREEN" -> new Color(0, 200, 0, alpha);
            default -> new Color(120, 120, 120, alpha);
        };
    }

    private Color getColorForType(String type) {
        if (type == null) return Color.RED;
        return switch (type) {
            case "drill_mk1" -> Color.LIGHT_GRAY;
            case "conveyor_belt" -> Color.DARK_GRAY;
            case "nexus_core" -> new Color(150, 0, 150);
            case "chromator" -> new Color(255, 140, 0);
            case "color_mixer" -> new Color(0, 200, 200);
            case "splitter" -> new Color(100, 149, 237);
            case "merger" -> new Color(70, 130, 180);
            case "lift" -> new Color(0, 139, 139);
            case "dropper" -> new Color(139, 0, 139);
            default -> Color.RED;
        };
    }
}
