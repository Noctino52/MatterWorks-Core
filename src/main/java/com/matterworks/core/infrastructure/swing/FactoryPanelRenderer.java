package com.matterworks.core.infrastructure.swing;

import com.google.gson.JsonObject;
import com.matterworks.core.common.Direction;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.domain.machines.*;
import com.matterworks.core.domain.matter.MatterColor;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.IdentityHashMap;
import java.util.Map;

final class FactoryPanelRenderer {

    static final int GRID_SIZE = 20;
    static final int CELL_SIZE = 40;
    static final int OFFSET_X = 50;
    static final int OFFSET_Y = 50;

    private static final Font FONT_TINY = new Font("SansSerif", Font.PLAIN, 9);
    private static final Font FONT_SMALL = new Font("SansSerif", Font.PLAIN, 10);
    private static final Font FONT_SYMBOL = new Font("Monospaced", Font.BOLD, 20);

    private static final Color BG = new Color(30, 30, 30);
    private static final Color GRID_COLOR = new Color(50, 50, 50);
    private static final Color GHOST_BORDER = Color.WHITE;
    private static final Color ARROW_COLOR = Color.YELLOW;

    private final BlockRegistry registry;
    private final FactoryPanelController controller;
    private final FactoryPanelState state;

    private volatile BufferedImage gridImage;
    private volatile int gridImageW = -1;
    private volatile int gridImageH = -1;

    // ghost cache
    private volatile String cachedGhostTool = null;
    private volatile Direction cachedGhostOri = null;
    private volatile Vector3Int cachedGhostDim = null;

    FactoryPanelRenderer(BlockRegistry registry, FactoryPanelController controller, FactoryPanelState state) {
        this.registry = registry;
        this.controller = controller;
        this.state = state;
    }

    void invalidateGhostCache() {
        cachedGhostTool = null;
        cachedGhostOri = null;
        cachedGhostDim = null;
    }

    void paint(Graphics g, int width, int height) {
        Graphics2D g2 = (Graphics2D) g;
        g2.setColor(BG);
        g2.fillRect(0, 0, width, height);

        ensureGridImage(width, height);
        if (gridImage != null) g2.drawImage(gridImage, 0, 0, null);

        var snap = controller.getSnapshot();

        if (state.currentLayer == 0) drawTerrainResources(g2, snap.resources());
        drawMachines(g2, snap.machines());
        drawGhost(g2);
    }

    private void ensureGridImage(int w, int h) {
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

    private void drawTerrainResources(Graphics2D g, Map<GridPosition, MatterColor> resources) {
        if (resources == null || resources.isEmpty()) return;
        g.setFont(FONT_TINY);

        for (var entry : resources.entrySet()) {
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

    private void drawMachines(Graphics2D g, Map<GridPosition, PlacedMachine> machines) {
        if (machines == null || machines.isEmpty()) return;

        IdentityHashMap<PlacedMachine, Boolean> seen = new IdentityHashMap<>();
        for (PlacedMachine m : machines.values()) {
            if (m == null) continue;
            if (seen.put(m, Boolean.TRUE) != null) continue;

            GridPosition pos = m.getPos();
            if (pos == null) continue;

            // layer visibility basata su registry (non su m.getDimensions)
            Vector3Int base = registry.getDimensions(m.getTypeId());
            if (base == null) base = Vector3Int.one();
            int ySize = base.y();
            int baseY = pos.y();
            if (state.currentLayer < baseY || state.currentLayer >= baseY + ySize) continue;

            drawSingleMachine(g, m);
        }
    }

    private void drawSingleMachine(Graphics2D g, PlacedMachine m) {
        GridPosition p = m.getPos();
        int x = OFFSET_X + (p.x() * CELL_SIZE);
        int z = OFFSET_Y + (p.z() * CELL_SIZE);

        // ✅ footprint “authoritative”: registry dims + orientation
        Vector3Int eff = getEffectiveFootprint(m.getTypeId(), m.getOrientation());
        int w = eff.x() * CELL_SIZE;
        int h = eff.z() * CELL_SIZE;

        if ("STRUCTURE_GENERIC".equals(m.getTypeId())) {
            g.setColor(new Color(80, 80, 80));
            g.fillRect(x + 2, z + 2, Math.max(0, w - 4), Math.max(0, h - 4));

            g.setColor(Color.WHITE);
            g.setFont(FONT_SMALL);

            String nativeId = "Block";
            JsonObject meta = controller.getMetaCached(m);
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

        // ports
        if ("nexus_core".equals(type)) {
            drawNexusPortsGrid(g, m, x, z);
        } else if ("splitter".equals(type)) {
            drawSplitterPortsGrid(g, m, x, z);
        } else if ("merger".equals(type)) {
            drawMergerPortsGrid(g, m, x, z);
        } else if ("chromator".equals(type) || "color_mixer".equals(type)) {
            drawTwoInputsOneOutput_2x1(g, m, x, z);
        } else if ("smoothing".equals(type) || "cutting".equals(type)) {
            // shaper/cutting: 1 porta concettuale front/back ma 2 punti d’aggancio su 2x1
            drawFrontBackPorts_2x1(g, m, x, z);
        } else if ("lift".equals(type) || "dropper".equals(type)) {
            drawVerticalPorts(g, m, x, z);
        } else {
            drawStandardPorts(g, m, x, z, w, h);
        }

        // items
        if (m instanceof ConveyorBelt belt) drawBeltItem(g, belt, x, z);
        if (m instanceof Splitter splitter) drawSplitterItem(g, splitter, x, z);
        if (m instanceof Merger merger) drawMergerItem(g, merger, x, z);

        // direction dot
        drawDirectionDot(g, x, z, w, h, m.getOrientation());
    }

    // --- helper: disegna un port sul bordo di UNA cella della footprint ---
    private void drawPortOnCellEdge(Graphics2D g, int baseX, int baseZ, int cellDx, int cellDz, Direction edge, Color c) {
        int p = 8;
        int cellX = baseX + cellDx * CELL_SIZE;
        int cellZ = baseZ + cellDz * CELL_SIZE;

        Point pt = switch (edge) {
            case NORTH -> new Point(cellX + CELL_SIZE / 2 - p / 2, cellZ);
            case SOUTH -> new Point(cellX + CELL_SIZE / 2 - p / 2, cellZ + CELL_SIZE - p);
            case EAST  -> new Point(cellX + CELL_SIZE - p, cellZ + CELL_SIZE / 2 - p / 2);
            case WEST  -> new Point(cellX, cellZ + CELL_SIZE / 2 - p / 2);
            default -> new Point(cellX + CELL_SIZE / 2 - p / 2, cellZ + CELL_SIZE / 2 - p / 2);
        };

        drawPort(g, pt, c);
    }
    private void drawPortAtCellCenter(Graphics2D g, int baseX, int baseZ, int cellDx, int cellDz, Color c) {
        int p = 8;
        int cellX = baseX + cellDx * CELL_SIZE;
        int cellZ = baseZ + cellDz * CELL_SIZE;
        Point pt = new Point(cellX + CELL_SIZE / 2 - p / 2, cellZ + CELL_SIZE / 2 - p / 2);
        drawPort(g, pt, c);
    }


    private int[] extensionOffset2x1(Direction o) {
        return switch (o) {
            case NORTH -> new int[]{ 1, 0 };
            case SOUTH -> new int[]{-1, 0 };
            case EAST  -> new int[]{ 0, 1 };
            case WEST  -> new int[]{ 0,-1 };
            default -> new int[]{ 1, 0 };
        };
    }

    // --- NEXUS: porte input reali (dx/dz coerenti con isValidInputPort) ---
    private void drawNexusPortsGrid(Graphics2D g, PlacedMachine m, int baseX, int baseZ) {
        int relY = state.currentLayer - m.getPos().y();
        // Nexus accetta input solo sui livelli dy 0 e 1 (vedi NexusMachine)
        if (relY < 0 || relY > 1) return;

        // Porte centrali: N,S,W,E
        drawPortOnCellEdge(g, baseX, baseZ, 1, 0, Direction.NORTH, Color.BLUE); // from (x+1, z-1)
        drawPortOnCellEdge(g, baseX, baseZ, 1, 2, Direction.SOUTH, Color.BLUE); // from (x+1, z+3)
        drawPortOnCellEdge(g, baseX, baseZ, 0, 1, Direction.WEST,  Color.BLUE); // from (x-1, z+1)
        drawPortOnCellEdge(g, baseX, baseZ, 2, 1, Direction.EAST,  Color.BLUE); // from (x+3, z+1)

        // Nota: il Nexus non ha output verso belt, quindi niente verde qui (prima era fuorviante).
    }

    // --- SPLITTER: 1 input (back anchor), 2 output (front anchor + front extension) ---
    private void drawSplitterPortsGrid(Graphics2D g, PlacedMachine m, int baseX, int baseZ) {
        Direction front = m.getOrientation();
        Direction back = front.opposite();
        int[] ext = extensionOffset2x1(front);

        // input: retro ANCHOR
        drawPortOnCellEdge(g, baseX, baseZ, 0, 0, back, Color.BLUE);

        // outputs: fronte ANCHOR + fronte EXTENSION
        drawPortOnCellEdge(g, baseX, baseZ, 0, 0, front, Color.GREEN);
        drawPortOnCellEdge(g, baseX, baseZ, ext[0], ext[1], front, Color.GREEN);
    }

    // --- MERGER: 2 input (back anchor + back extension), 1 output (front anchor) ---
    private void drawMergerPortsGrid(Graphics2D g, PlacedMachine m, int baseX, int baseZ) {
        Direction front = m.getOrientation();
        Direction back = front.opposite();
        int[] ext = extensionOffset2x1(front);

        drawPortOnCellEdge(g, baseX, baseZ, 0, 0, back, Color.BLUE);
        drawPortOnCellEdge(g, baseX, baseZ, ext[0], ext[1], back, Color.BLUE);

        drawPortOnCellEdge(g, baseX, baseZ, 0, 0, front, Color.GREEN);
    }

    // --- CHROMATOR / MIXER: 2 input (back su entrambe le celle), 1 output (front sulla cella "anchor-side") ---
    private void drawTwoInputsOneOutput_2x1(Graphics2D g, PlacedMachine m, int baseX, int baseZ) {
        Direction o = m.getOrientation();

        // Celle footprint in base all’orientamento:
        // N/S: (0,0) e (1,0)   | E/W: (0,0) e (0,1)
        if (o == Direction.NORTH) {
            // input back = SOUTH su entrambe
            drawPortOnCellEdge(g, baseX, baseZ, 0, 0, Direction.SOUTH, Color.BLUE);
            drawPortOnCellEdge(g, baseX, baseZ, 1, 0, Direction.SOUTH, Color.BLUE);
            // output front = NORTH sulla cella sinistra (coerente con getOutputPosition di Chromator/Mixer)
            drawPortOnCellEdge(g, baseX, baseZ, 0, 0, Direction.NORTH, Color.GREEN);
        } else if (o == Direction.SOUTH) {
            drawPortOnCellEdge(g, baseX, baseZ, 0, 0, Direction.NORTH, Color.BLUE);
            drawPortOnCellEdge(g, baseX, baseZ, 1, 0, Direction.NORTH, Color.BLUE);
            // output front = SOUTH sulla cella destra
            drawPortOnCellEdge(g, baseX, baseZ, 1, 0, Direction.SOUTH, Color.GREEN);
        } else if (o == Direction.EAST) {
            drawPortOnCellEdge(g, baseX, baseZ, 0, 0, Direction.WEST, Color.BLUE);
            drawPortOnCellEdge(g, baseX, baseZ, 0, 1, Direction.WEST, Color.BLUE);
            // output front = EAST sulla cella anchor (0,0)
            drawPortOnCellEdge(g, baseX, baseZ, 0, 0, Direction.EAST, Color.GREEN);
        } else if (o == Direction.WEST) {
            drawPortOnCellEdge(g, baseX, baseZ, 0, 0, Direction.EAST, Color.BLUE);
            drawPortOnCellEdge(g, baseX, baseZ, 0, 1, Direction.EAST, Color.BLUE);
            // output front = WEST sulla cella extension (0,1)
            drawPortOnCellEdge(g, baseX, baseZ, 0, 1, Direction.WEST, Color.GREEN);
        }
    }

    // --- SHAPER / CUTTING: front/back “porta unica” ma 2 punti aggancio sulla 2x1 ---
    private void drawFrontBackPorts_2x1(Graphics2D g, PlacedMachine m, int baseX, int baseZ) {
        Direction front = m.getOrientation();
        Direction back = front.opposite();

        // footprint 2x1: anchor + extension (speculare)
        int[] ext = extensionOffset2x1(front); // N:{+1,0} S:{-1,0} E:{0,+1} W:{0,-1}
        int[][] cells = new int[][] { {0, 0}, {ext[0], ext[1]} };

        // ✅ SHIFT “come hai detto”: SOLO per SOUTH e WEST -> +1 a destra (+X) e +1 in basso (+Z)
        int shiftX = (front == Direction.SOUTH || front == Direction.WEST) ? 1 : 0;
        int shiftZ = (front == Direction.SOUTH || front == Direction.WEST) ? 1 : 0;

        if (front == Direction.NORTH || front == Direction.SOUTH) {
            // INPUT (blu): 1 blocco dietro (cella back), sul bordo estremo (N/S)
            int backDz = (front == Direction.NORTH) ? 1 : -1;
            for (int[] c : cells) {
                int cx = c[0] + shiftX;
                int cz = c[1] + backDz + shiftZ;
                drawPortOnCellEdge(g, baseX, baseZ, cx, cz, back, Color.BLUE);
            }

            // OUTPUT (verde): sul bordo front della footprint (come prima)
            for (int[] c : cells) {
                int cx = c[0] + shiftX;
                int cz = c[1] + shiftZ;
                drawPortOnCellEdge(g, baseX, baseZ, cx, cz, front, Color.GREEN);
            }

        } else {
            // INPUT (blu): sul bordo back della footprint
            for (int[] c : cells) {
                int cx = c[0] + shiftX;
                int cz = c[1] + shiftZ;
                drawPortOnCellEdge(g, baseX, baseZ, cx, cz, back, Color.BLUE);
            }

            // OUTPUT (verde): 1 blocco avanti (cella front), sul bordo estremo E/W
            int frontDx = (front == Direction.EAST) ? 1 : -1;
            for (int[] c : cells) {
                int cx = c[0] + frontDx + shiftX;
                int cz = c[1] + shiftZ;
                drawPortOnCellEdge(g, baseX, baseZ, cx, cz, front, Color.GREEN);
            }
        }
    }










    private Vector3Int getEffectiveFootprint(String typeId, Direction ori) {
        Vector3Int base = registry.getDimensions(typeId);
        if (base == null) base = Vector3Int.one();
        int dx = base.x();
        int dz = base.z();
        if (ori == Direction.EAST || ori == Direction.WEST) {
            int tmp = dx; dx = dz; dz = tmp;
        }
        return new Vector3Int(dx, base.y(), dz);
    }

    private void drawGhost(Graphics2D g) {
        GridPosition hover = state.mouseHoverPos;
        String tool = state.currentTool;
        if (hover == null || tool == null) return;

        Vector3Int dim = getGhostDimsCached();
        int x = OFFSET_X + (hover.x() * CELL_SIZE);
        int z = OFFSET_Y + (hover.z() * CELL_SIZE);

        Color fill = tool.startsWith("STRUCTURE:") ? Color.LIGHT_GRAY : getColorForType(tool);

        Composite old = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f));
        g.setColor(fill);
        g.fillRect(x, z, dim.x() * CELL_SIZE, dim.z() * CELL_SIZE);
        g.setComposite(old);

        g.setColor(GHOST_BORDER);
        g.drawRect(x, z, dim.x() * CELL_SIZE, dim.z() * CELL_SIZE);

        drawDirectionDot(g, x, z, dim.x() * CELL_SIZE, dim.z() * CELL_SIZE, state.currentOrientation);
    }

    private Vector3Int getGhostDimsCached() {
        String tool = state.currentTool;
        Direction ori = state.currentOrientation;
        if (tool == null) return Vector3Int.one();

        if (cachedGhostDim != null && tool.equals(cachedGhostTool) && ori == cachedGhostOri) {
            return cachedGhostDim;
        }

        Vector3Int base;
        if (tool.startsWith("STRUCTURE:")) {
            base = Vector3Int.one();
        } else {
            base = registry.getDimensions(tool);
            if (base == null) base = Vector3Int.one();
        }

        int dx = base.x();
        int dz = base.z();
        if (ori == Direction.EAST || ori == Direction.WEST) {
            int tmp = dx; dx = dz; dz = tmp;
        }

        cachedGhostTool = tool;
        cachedGhostOri = ori;
        cachedGhostDim = new Vector3Int(dx, base.y(), dz);
        return cachedGhostDim;
    }

    // ===== ports + items + utils =====

    private void drawVerticalPorts(Graphics2D g, PlacedMachine m, int x, int z) {
        int p = 8;
        int c = CELL_SIZE;
        int relativeY = state.currentLayer - m.getPos().y();
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
        JsonObject meta = controller.getMetaCached(belt);
        if (meta == null) return;
        if (!meta.has("currentItem")) return;
        if (!meta.get("currentItem").isJsonObject()) return;
        drawItemShape(g, meta.getAsJsonObject("currentItem"), x, z);
    }

    private void drawSplitterItem(Graphics2D g, Splitter splitter, int x, int z) {
        JsonObject meta = controller.getMetaCached(splitter);
        if (meta == null) return;
        if (meta.has("items") && meta.getAsJsonArray("items").size() > 0) {
            var item = meta.getAsJsonArray("items").get(0);
            if (!item.isJsonNull() && item.isJsonObject()) drawItemShape(g, item.getAsJsonObject(), x, z);
        }
    }

    private void drawMergerItem(Graphics2D g, Merger merger, int x, int z) {
        JsonObject meta = controller.getMetaCached(merger);
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
        } else if ("PYRAMID".equals(shapeStr)) {
            int x0 = x + pad + size / 2;
            int y0 = z + pad;
            int x1 = x + pad;
            int y1 = z + pad + size;
            int x2 = x + pad + size;
            int y2 = z + pad + size;
            Polygon tri = new Polygon(new int[]{x0, x1, x2}, new int[]{y0, y1, y2}, 3);
            g.fillPolygon(tri);
        } else {
            g.fillOval(x + pad, z + pad, size, size);
        }


        g.setColor(Color.WHITE);
        g.drawRect(x + pad, z + pad, size, size);
    }

    private void drawDirectionDot(Graphics2D g, int x, int y, int w, int h, Direction dir) {
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
            case "smoothing" -> new Color(46, 204, 113);
            case "cutting" -> new Color(241, 196, 15);
            default -> Color.RED;
        };
    }
}
