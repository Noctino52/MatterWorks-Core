package com.matterworks.core.infrastructure.swing;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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

    private static final Color EFFECT_SHINY = new Color(235, 235, 235);
    private static final Color EFFECT_BLAZE = new Color(255, 110, 0);
    private static final Color EFFECT_GLITCH = new Color(255, 0, 220);
    private static final Color EFFECT_NONE = new Color(210, 210, 210);

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

    // machine lookup for this paint frame (cell -> occupant machine)
    private Map<GridPosition, PlacedMachine> machineGrid = Map.of();

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

        // cache griglia occupazione per risolvere i port come fa la logica
        this.machineGrid = buildExpandedOccupancy((snap.machines() != null) ? snap.machines() : Map.of());

        if (state.currentLayer == 0) drawTerrainResources(g2, snap.resources());
        drawMachines(g2, snap.machines());
        drawGhost(g2);
    }

    private Map<GridPosition, PlacedMachine> buildExpandedOccupancy(Map<GridPosition, PlacedMachine> machines) {
        if (machines == null || machines.isEmpty()) return Map.of();

        java.util.HashMap<GridPosition, PlacedMachine> out = new java.util.HashMap<>();
        java.util.IdentityHashMap<PlacedMachine, Boolean> seen = new java.util.IdentityHashMap<>();

        for (PlacedMachine m : machines.values()) {
            if (m == null) continue;
            if (seen.put(m, Boolean.TRUE) != null) continue;

            GridPosition p = m.getPos();
            if (p == null) continue;

            out.put(p, m);

            Vector3Int dim = registry.getDimensions(m.getTypeId());
            if (dim != null && dim.x() == 2 && dim.z() == 1) {
                int[] ext = extensionOffset2x1(m.getOrientation());
                GridPosition p2 = new GridPosition(p.x() + ext[0], p.y(), p.z() + ext[1]);
                out.put(p2, m);
                continue;
            }

            if (dim != null) {
                Vector3Int eff = getEffectiveFootprint(m.getTypeId(), m.getOrientation());
                for (int dx = 0; dx < eff.x(); dx++) {
                    for (int dz = 0; dz < eff.z(); dz++) {
                        out.put(new GridPosition(p.x() + dx, p.y(), p.z() + dz), m);
                    }
                }
            }
        }

        return out;
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
        } else if ("smoothing".equals(type) || "cutting".equals(type)
                || "shiny_polisher".equals(type) || "blazing_forge".equals(type) || "glitch_distorter".equals(type)) {
            // ✅ come cutting/shaper: porta risolta fuori footprint + disegnata sul bordo interno
            drawShaperCuttingPortsSingle(g, m, x, z);
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

    // ======= Machine lookup (cell -> occupant) =======

    private PlacedMachine getMachineAt(GridPosition p) {
        if (p == null) return null;
        Map<GridPosition, PlacedMachine> grid = machineGrid;
        if (grid == null || grid.isEmpty()) return null;
        return grid.get(p);
    }

    /**
     * Replica ESATTA della logica stepOutOfSelf:
     * parte da start, se la cella è ancora occupata dalla stessa istanza di m,
     * avanza di un altro step nella stessa direzione finché esce (max 3 step).
     */
    private GridPosition resolvePortCellOutsideFootprint(PlacedMachine m, GridPosition start, Direction stepDir) {
        GridPosition p = start;
        Vector3Int step = stepDir.toVector();

        for (int i = 0; i < 3; i++) {
            PlacedMachine at = getMachineAt(p);
            if (at == null || at != m) return p;
            p = new GridPosition(p.x() + step.x(), p.y() + step.y(), p.z() + step.z());
        }
        return p;
    }

    // ======= Ports drawing helpers =======

    private void drawPortOnCellEdge(Graphics2D g, int baseX, int baseZ, int cellDx, int cellDz, Direction edge, Color c) {
        int p = 8;
        int cellX = (baseX + cellDx * CELL_SIZE);
        int cellZ = (baseZ + cellDz * CELL_SIZE);

        Point pt = switch (edge) {
            case NORTH -> new Point(cellX + CELL_SIZE / 2 - p / 2, cellZ);
            case SOUTH -> new Point(cellX + CELL_SIZE / 2 - p / 2, cellZ + CELL_SIZE - p);
            case EAST  -> new Point(cellX + CELL_SIZE - p, cellZ + CELL_SIZE / 2 - p / 2);
            case WEST  -> new Point(cellX, cellZ + CELL_SIZE / 2 - p / 2);
            default -> new Point(cellX + CELL_SIZE / 2 - p / 2, cellZ + CELL_SIZE / 2 - p / 2);
        };

        drawPort(g, pt, c);
    }

    private int[] extensionOffset2x1(Direction o) {
        return switch (o) {
            case NORTH -> new int[]{1, 0};
            case SOUTH -> new int[]{-1, 0};
            case EAST  -> new int[]{0, 1};
            case WEST  -> new int[]{0, -1};
            default -> new int[]{1, 0};
        };
    }

    // NEXUS: real input ports (coherent with NexusMachine isValidInputPort)
    private void drawNexusPortsGrid(Graphics2D g, PlacedMachine m, int baseX, int baseZ) {
        int relY = state.currentLayer - m.getPos().y();
        if (relY < 0 || relY > 1) return;

        drawPortOnCellEdge(g, baseX, baseZ, 1, 0, Direction.NORTH, Color.BLUE);
        drawPortOnCellEdge(g, baseX, baseZ, 1, 2, Direction.SOUTH, Color.BLUE);
        drawPortOnCellEdge(g, baseX, baseZ, 0, 1, Direction.WEST, Color.BLUE);
        drawPortOnCellEdge(g, baseX, baseZ, 2, 1, Direction.EAST, Color.BLUE);
    }

    // SPLITTER: 1 input (back anchor), 2 outputs (front anchor + front extension)
    private void drawSplitterPortsGrid(Graphics2D g, PlacedMachine m, int baseX, int baseZ) {
        Direction front = m.getOrientation();
        Direction back = front.opposite();
        int[] ext = extensionOffset2x1(front);

        drawPortOnCellEdge(g, baseX, baseZ, 0, 0, back, Color.BLUE);
        drawPortOnCellEdge(g, baseX, baseZ, 0, 0, front, Color.GREEN);
        drawPortOnCellEdge(g, baseX, baseZ, ext[0], ext[1], front, Color.GREEN);
    }

    // MERGER: 2 inputs (back anchor + back extension), 1 output (front anchor)
    private void drawMergerPortsGrid(Graphics2D g, PlacedMachine m, int baseX, int baseZ) {
        Direction front = m.getOrientation();
        Direction back = front.opposite();
        int[] ext = extensionOffset2x1(front);

        drawPortOnCellEdge(g, baseX, baseZ, 0, 0, back, Color.BLUE);
        drawPortOnCellEdge(g, baseX, baseZ, ext[0], ext[1], back, Color.BLUE);
        drawPortOnCellEdge(g, baseX, baseZ, 0, 0, front, Color.GREEN);
    }

    // CHROMATOR / MIXER: 2 inputs (back on both cells), 1 output
    private void drawTwoInputsOneOutput_2x1(Graphics2D g, PlacedMachine m, int baseX, int baseZ) {
        Direction o = m.getOrientation();

        if (o == Direction.NORTH) {
            drawPortOnCellEdge(g, baseX, baseZ, 0, 0, Direction.SOUTH, Color.BLUE);
            drawPortOnCellEdge(g, baseX, baseZ, 1, 0, Direction.SOUTH, Color.BLUE);
            drawPortOnCellEdge(g, baseX, baseZ, 0, 0, Direction.NORTH, Color.GREEN);
        } else if (o == Direction.SOUTH) {
            drawPortOnCellEdge(g, baseX, baseZ, 0, 0, Direction.NORTH, Color.BLUE);
            drawPortOnCellEdge(g, baseX, baseZ, 1, 0, Direction.NORTH, Color.BLUE);
            drawPortOnCellEdge(g, baseX, baseZ, 1, 0, Direction.SOUTH, Color.GREEN);
        } else if (o == Direction.EAST) {
            drawPortOnCellEdge(g, baseX, baseZ, 0, 0, Direction.WEST, Color.BLUE);
            drawPortOnCellEdge(g, baseX, baseZ, 0, 1, Direction.WEST, Color.BLUE);
            drawPortOnCellEdge(g, baseX, baseZ, 0, 0, Direction.EAST, Color.GREEN);
        } else if (o == Direction.WEST) {
            drawPortOnCellEdge(g, baseX, baseZ, 0, 0, Direction.EAST, Color.BLUE);
            drawPortOnCellEdge(g, baseX, baseZ, 0, 1, Direction.EAST, Color.BLUE);
            drawPortOnCellEdge(g, baseX, baseZ, 0, 1, Direction.WEST, Color.GREEN);
        }
    }

    /**
     * smoothing/cutting + effect-machines:
     * draw ports exactly where logic expects them (stepOutOfSelf) and put the square on the machine edge.
     */
    private void drawShaperCuttingPortsSingle(Graphics2D g, PlacedMachine m, int baseX, int baseZ) {
        Direction front = m.getOrientation();
        Direction back = front.opposite();

        GridPosition pos = m.getPos();

        Vector3Int fv = front.toVector();
        Vector3Int bv = back.toVector();

        GridPosition outStart = new GridPosition(pos.x() + fv.x(), pos.y() + fv.y(), pos.z() + fv.z());
        GridPosition inStart  = new GridPosition(pos.x() + bv.x(), pos.y() + bv.y(), pos.z() + bv.z());

        GridPosition outCell = resolvePortCellOutsideFootprint(m, outStart, front);
        GridPosition inCell  = resolvePortCellOutsideFootprint(m, inStart,  back);

        int dxOut = outCell.x() - pos.x();
        int dzOut = outCell.z() - pos.z();
        int dxIn  = inCell.x()  - pos.x();
        int dzIn  = inCell.z()  - pos.z();

        // disegno sul bordo della cella interna, non sul belt
        int dxInInside  = dxIn  + fv.x();
        int dzInInside  = dzIn  + fv.z();

        int dxOutInside = dxOut + bv.x();
        int dzOutInside = dzOut + bv.z();

        drawPortOnCellEdge(g, baseX, baseZ, dxInInside,  dzInInside,  back,  Color.BLUE);
        drawPortOnCellEdge(g, baseX, baseZ, dxOutInside, dzOutInside, front, Color.GREEN);
    }

    // ======= Footprint / Ghost =======

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

    // ======= Vertical machines =======

    private void drawVerticalPorts(Graphics2D g, PlacedMachine m, int x, int z) {
        int p = 8;
        int c = CELL_SIZE;
        int relativeY = state.currentLayer - m.getPos().y();
        boolean isLift = "lift".equals(m.getTypeId());
        Point front = null, back = null;

        switch (m.getOrientation()) {
            case NORTH -> { front = new Point(x + c / 2 - p / 2, z); back = new Point(x + c / 2 - p / 2, z + c - p); }
            case SOUTH -> { front = new Point(x + c / 2 - p / 2, z + c - p); back = new Point(x + c / 2 - p / 2, z); }
            case EAST  -> { front = new Point(x + c - p, z + c / 2 - p / 2); back = new Point(x, z + c / 2 - p / 2); }
            case WEST  -> { front = new Point(x, z + c / 2 - p / 2); back = new Point(x + c - p, z + c / 2 - p / 2); }
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

    // ======= Standard ports/items =======

    private void drawStandardPorts(Graphics2D g, PlacedMachine m, int x, int z, int w, int h) {
        int p = 8;
        Point front = null, back = null;

        switch (m.getOrientation()) {
            case NORTH -> { front = new Point(x + w / 2 - p / 2, z); back = new Point(x + w / 2 - p / 2, z + h - p); }
            case SOUTH -> { front = new Point(x + w / 2 - p / 2, z + h - p); back = new Point(x + w / 2 - p / 2, z); }
            case EAST  -> { front = new Point(x + w - p, z + h / 2 - p / 2); back = new Point(x, z + h / 2 - p / 2); }
            case WEST  -> { front = new Point(x, z + h / 2 - p / 2); back = new Point(x + w - p, z + h / 2 - p / 2); }
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

    // ======= ITEM RENDER (color + shape + effect overlay) =======

    private void drawItemShape(Graphics2D g, JsonObject item, int x, int z) {
        String colorStr = item.has("color") ? item.get("color").getAsString() : "RAW";
        String shapeStr = (item.has("shape") && !item.get("shape").isJsonNull())
                ? item.get("shape").getAsString()
                : "LIQUID";

        String effect = readSingleEffect(item); // null se none

        int size = 18;
        int pad = (CELL_SIZE - size) / 2;

        // fill
        g.setColor(getColorFromStr(colorStr, 255));
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

        // outline per distinguere NONE vs EFFECT
        Color outline = effectColor(effect);
        g.setColor(outline);
        g.drawRect(x + pad, z + pad, size, size);

        // overlay effetto (badge + segno grafico)
        if (effect != null) {
            drawEffectOverlay(g, effect, x + pad, z + pad, size);
        }
    }

    private String readSingleEffect(JsonObject item) {
        if (item == null) return null;
        if (!item.has("effects")) return null;
        JsonElement el = item.get("effects");
        if (!el.isJsonArray()) return null;
        JsonArray arr = el.getAsJsonArray();
        if (arr.isEmpty()) return null;
        JsonElement first = arr.get(0);
        if (first == null || first.isJsonNull()) return null;
        String s = first.getAsString();
        return (s == null || s.isBlank()) ? null : s;
    }

    private Color effectColor(String effect) {
        if (effect == null) return EFFECT_NONE;
        return switch (effect) {
            case "SHINY" -> EFFECT_SHINY;
            case "BLAZING" -> EFFECT_BLAZE;
            case "GLITCH" -> EFFECT_GLITCH;
            default -> Color.WHITE;
        };
    }

    private void drawEffectOverlay(Graphics2D g, String effect, int x, int y, int size) {
        // badge in alto a destra
        int b = 8;
        g.setColor(effectColor(effect));
        g.fillRect(x + size - b, y, b, b);
        g.setColor(Color.BLACK);
        g.drawRect(x + size - b, y, b, b);

        // segno grafico principale
        if ("SHINY".equals(effect)) {
            // sparkle: croce + diagonali leggere
            g.setColor(EFFECT_SHINY);
            int cx = x + size / 2;
            int cy = y + size / 2;
            g.drawLine(cx - 6, cy, cx + 6, cy);
            g.drawLine(cx, cy - 6, cx, cy + 6);
            g.drawLine(cx - 4, cy - 4, cx + 4, cy + 4);
            g.drawLine(cx - 4, cy + 4, cx + 4, cy - 4);
        } else if ("BLAZING".equals(effect)) {
            // flame: triangolino/fiammella in basso
            g.setColor(EFFECT_BLAZE);
            int fx = x + size / 2;
            int fy = y + size - 2;
            Polygon flame = new Polygon(
                    new int[]{fx, fx - 5, fx + 5},
                    new int[]{fy - 10, fy, fy},
                    3
            );
            g.fillPolygon(flame);
        } else if ("GLITCH".equals(effect)) {
            // glitch: 3 righe “disturbate”
            g.setColor(EFFECT_GLITCH);
            g.drawLine(x + 2, y + 5, x + size - 2, y + 5);
            g.drawLine(x + 1, y + 9, x + size - 4, y + 9);
            g.drawLine(x + 3, y + 13, x + size - 1, y + 13);
        }
    }

    private void drawDirectionDot(Graphics2D g, int x, int y, int w, int h, Direction dir) {
        g.setColor(ARROW_COLOR);
        int cx = x + w / 2;
        int cy = y + h / 2;
        switch (dir) {
            case NORTH -> g.fillOval(cx - 3, y + 2, 6, 6);
            case SOUTH -> g.fillOval(cx - 3, y + h - 8, 6, 6);
            case EAST  -> g.fillOval(x + w - 8, cy - 3, 6, 6);
            case WEST  -> g.fillOval(x + 2, cy - 3, 6, 6);
        }
    }

    private Color getColorFromStr(String c, int alpha) {
        if (c == null) return new Color(120, 120, 120, alpha);
        return switch (c) {
            case "RAW" -> new Color(120, 120, 120, alpha);
            case "RED" -> new Color(200, 0, 0, alpha);
            case "BLUE" -> new Color(0, 0, 200, alpha);
            case "YELLOW" -> new Color(200, 200, 0, alpha);
            case "PURPLE" -> new Color(160, 32, 240, alpha);
            case "GREEN" -> new Color(0, 200, 0, alpha);
            case "ORANGE" -> new Color(255, 140, 0, alpha);
            case "WHITE" -> new Color(240, 240, 240, alpha);
            default -> new Color(120, 120, 120, alpha);
        };
    }

    // ======= Machine colors =======

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

            // ✅ nuovi: colori distinti
            case "shiny_polisher" -> new Color(190, 190, 190);      // metallizzato
            case "blazing_forge" -> new Color(210, 70, 0);          // arancio/rossastro
            case "glitch_distorter" -> new Color(170, 0, 210);      // viola/magenta

            default -> Color.RED;
        };
    }
}
