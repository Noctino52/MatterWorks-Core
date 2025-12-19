package com.matterworks.core.infrastructure.swing;

import com.google.gson.JsonObject;
import com.matterworks.core.common.Direction;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.domain.machines.BlockRegistry;
import com.matterworks.core.domain.machines.ConveyorBelt;
import com.matterworks.core.domain.machines.PlacedMachine;
import com.matterworks.core.domain.machines.Splitter; // Import necessario
import com.matterworks.core.domain.matter.MatterColor;
import com.matterworks.core.managers.GridManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.Map;
import java.util.UUID;

public class FactoryPanel extends JPanel {

    private final GridManager gridManager;
    private final BlockRegistry registry;
    private UUID playerUuid;

    private final int CELL_SIZE = 40;
    private final int OFFSET_X = 50;
    private final int OFFSET_Y = 50;

    private String currentTool = "drill_mk1";
    private Direction currentOrientation = Direction.NORTH;
    private int currentLayer = 0;

    private GridPosition mouseHoverPos = null;
    private Runnable onStateChange;

    public FactoryPanel(GridManager gridManager, BlockRegistry registry, UUID playerUuid, Runnable onStateChange) {
        this.gridManager = gridManager;
        this.registry = registry;
        this.playerUuid = playerUuid;
        this.onStateChange = onStateChange;
        this.setBackground(new Color(30, 30, 30));
        this.setFocusable(true);

        this.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) { updateMousePos(e.getX(), e.getY()); repaint(); }
        });

        this.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) { requestFocusInWindow(); handleMouseClick(e); repaint(); }
        });

        this.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) { if (e.getKeyCode() == KeyEvent.VK_R) rotate(); }
        });
    }

    public void setPlayerUuid(UUID uuid) { this.playerUuid = uuid; repaint(); }
    public void setLayer(int y) { this.currentLayer = y; repaint(); }
    public int getCurrentLayer() { return currentLayer; }
    public void setTool(String toolId) {
        this.currentTool = toolId;
        repaint();
    }
    public String getCurrentToolName() { return currentTool != null ? currentTool : "None"; }

    public void rotate() {
        switch(currentOrientation) {
            case NORTH -> currentOrientation = Direction.EAST;
            case EAST -> currentOrientation = Direction.SOUTH;
            case SOUTH -> currentOrientation = Direction.WEST;
            case WEST -> currentOrientation = Direction.NORTH;
        }
        if (onStateChange != null) onStateChange.run();
        repaint();
    }

    public String getCurrentOrientationName() { return currentOrientation.name(); }

    private void updateMousePos(int x, int y) {
        int gx = (x - OFFSET_X) / CELL_SIZE;
        int gz = (y - OFFSET_Y) / CELL_SIZE;
        if (gx >= 0 && gx <= 20 && gz >= 0 && gz <= 20) {
            this.mouseHoverPos = new GridPosition(gx, currentLayer, gz);
        } else {
            this.mouseHoverPos = null;
        }
    }

    private void handleMouseClick(MouseEvent e) {
        if (mouseHoverPos == null) return;
        if (SwingUtilities.isLeftMouseButton(e)) {
            gridManager.placeMachine(playerUuid, mouseHoverPos, currentTool, currentOrientation);
        } else if (SwingUtilities.isRightMouseButton(e)) {
            gridManager.removeComponent(playerUuid, mouseHoverPos);
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        if (currentLayer == 0) drawTerrainResources(g2);
        drawGrid(g2);
        drawMachines(g2);
        drawGhost(g2);
    }

    private void drawTerrainResources(Graphics2D g) {
        Map<GridPosition, MatterColor> resources = gridManager.getTerrainResources(playerUuid);
        for (Map.Entry<GridPosition, MatterColor> entry : resources.entrySet()) {
            GridPosition pos = entry.getKey();
            MatterColor type = entry.getValue();
            int x = OFFSET_X + (pos.x() * CELL_SIZE);
            int z = OFFSET_Y + (pos.z() * CELL_SIZE);

            g.setColor(getColorFromStr(type.name(), 100));
            g.fillRect(x, z, CELL_SIZE, CELL_SIZE);
            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.PLAIN, 9));
            g.drawString(type.name(), x + 2, z + 12);
        }
    }

    private void drawGrid(Graphics2D g) {
        g.setColor(new Color(50, 50, 50));
        for (int i = 0; i <= 20; i++) {
            g.drawLine(OFFSET_X + (i * CELL_SIZE), OFFSET_Y, OFFSET_X + (i * CELL_SIZE), OFFSET_Y + (20 * CELL_SIZE));
            g.drawLine(OFFSET_X, OFFSET_Y + (i * CELL_SIZE), OFFSET_X + (20 * CELL_SIZE), OFFSET_Y + (i * CELL_SIZE));
        }
    }

    private void drawMachines(Graphics2D g) {
        Map<GridPosition, PlacedMachine> machines = gridManager.getSnapshot(playerUuid);
        for (PlacedMachine m : machines.values()) {
            if (currentLayer >= m.getPos().y() && currentLayer < m.getPos().y() + m.getDimensions().y()) {
                drawSingleMachine(g, m);
            }
        }
    }

    private void drawSingleMachine(Graphics2D g, PlacedMachine m) {
        int x = OFFSET_X + (m.getPos().x() * CELL_SIZE);
        int z = OFFSET_Y + (m.getPos().z() * CELL_SIZE);

        // Calcola dimensioni visive in base all'orientamento
        // Se è EST/OVEST, scambia Width e Depth nel bounding box visivo
        Vector3Int dims = m.getDimensions(); // getDimensions() gestisce già lo swap W/D nel dominio

        int w = dims.x() * CELL_SIZE;
        int h = dims.z() * CELL_SIZE;

        g.setColor(getColorForType(m.getTypeId()));
        g.fillRect(x + 2, z + 2, w - 4, h - 4);

        // --- Logica porte specifica per tipo ---
        if (m.getTypeId().equals("nexus_core")) {
            drawNexusPorts(g, m, x, z, w, h);
        } else if (m.getTypeId().equals("chromator") || m.getTypeId().equals("color_mixer")) {
            drawProcessorPorts(g, m, x, z, w, h);
        } else if (m.getTypeId().equals("splitter")) {
            drawSplitterPorts(g, m, x, z, w, h);
        } else {
            drawStandardPorts(g, m, x, z, w, h);
        }

        // Draw Items
        if (m instanceof ConveyorBelt belt) drawBeltItem(g, belt, x, z);
        if (m instanceof Splitter splitter) drawSplitterItem(g, splitter, x, z); // NEW: Item nello splitter

        drawDirectionArrow(g, x, z, w, h, m.getOrientation());
    }

    private void drawStandardPorts(Graphics2D g, PlacedMachine m, int x, int z, int w, int h) {
        int p = 8;
        Point out = null, in = null;
        switch (m.getOrientation()) {
            case NORTH -> {
                out = new Point(x + w/2 - p/2, z);
                in = new Point(x + w/2 - p/2, z + h - p);
            }
            case SOUTH -> {
                out = new Point(x + w/2 - p/2, z + h - p);
                in = new Point(x + w/2 - p/2, z);
            }
            case EAST  -> {
                out = new Point(x + w - p, z + h/2 - p/2);
                in = new Point(x, z + h/2 - p/2);
            }
            case WEST  -> {
                out = new Point(x, z + h/2 - p/2);
                in = new Point(x + w - p, z + h/2 - p/2);
            }
        }
        if (out != null) drawPort(g, out, Color.GREEN);
        if (in != null && !m.getTypeId().equals("drill_mk1")) drawPort(g, in, Color.BLUE);
    }

    private void drawNexusPorts(Graphics2D g, PlacedMachine m, int x, int z, int w, int h) {
        if (currentLayer - m.getPos().y() <= 1) {
            int p = 8;
            // 4 Porte cardinali
            drawPort(g, new Point(x + w/2 - p/2, z), Color.BLUE);         // Nord
            drawPort(g, new Point(x + w/2 - p/2, z + h - p), Color.BLUE); // Sud
            drawPort(g, new Point(x, z + h/2 - p/2), Color.BLUE);         // Ovest
            drawPort(g, new Point(x + w - p, z + h/2 - p/2), Color.BLUE); // Est
        }
    }

    private void drawProcessorPorts(Graphics2D g, PlacedMachine m, int x, int z, int w, int h) {
        int p = 8;
        int c = CELL_SIZE;
        boolean isChroma = m.getTypeId().equals("chromator");
        // Colori per input
        Color colS0 = isChroma ? Color.CYAN : Color.BLUE; // Input Main
        Color colS1 = isChroma ? Color.MAGENTA : Color.BLUE; // Input Secondario

        switch (m.getOrientation()) {
            case NORTH -> {
                // Out (Centro Top)
                drawPort(g, new Point(x + c/2 - p/2, z), Color.GREEN);
                // In 0 (Sinistra Bottom - relativa al blocco)
                drawPort(g, new Point(x + c/2 - p/2, z + h - p), colS0);
                // In 1 (Destra Bottom - secondo blocco)
                drawPort(g, new Point(x + (c*3)/2 - p/2, z + h - p), colS1);
            }
            case SOUTH -> {
                // Out (Centro Bottom)
                drawPort(g, new Point(x + (c*3)/2 - p/2, z + h - p), Color.GREEN);
                // In 0 (Sinistra Top relativa)
                drawPort(g, new Point(x + (c*3)/2 - p/2, z), colS0);
                // In 1 (Destra Top relativa)
                drawPort(g, new Point(x + c/2 - p/2, z), colS1);
            }
            case EAST -> {
                // Out (Destra Centro)
                drawPort(g, new Point(x + w - p, z + c/2 - p/2), Color.GREEN);
                // In 0 (Sinistra Top)
                drawPort(g, new Point(x, z + c/2 - p/2), colS0);
                // In 1 (Sinistra Bottom)
                drawPort(g, new Point(x, z + (c*3)/2 - p/2), colS1);
            }
            case WEST -> {
                // Out (Sinistra Centro)
                drawPort(g, new Point(x, z + (c*3)/2 - p/2), Color.GREEN);
                // In 0 (Destra Bottom)
                drawPort(g, new Point(x + w - p, z + (c*3)/2 - p/2), colS0);
                // In 1 (Destra Top)
                drawPort(g, new Point(x + w - p, z + c/2 - p/2), colS1);
            }
        }
    }

    /**
     * Disegna le porte dello Splitter.
     * 1 Input (Retro dell'Anchor)
     * 2 Output (Fronte dell'Anchor e Fronte dell'Estensione)
     */
    private void drawSplitterPorts(Graphics2D g, PlacedMachine m, int x, int z, int w, int h) {
        int p = 8;
        int c = CELL_SIZE; // Dimensione di un singolo blocco

        switch (m.getOrientation()) {
            case NORTH -> {
                // Input: Sud dell'Anchor (x, z+1) -> Visivamente Bottom-Left
                drawPort(g, new Point(x + c/2 - p/2, z + c - p), Color.BLUE);

                // Out A: Nord dell'Anchor -> Top-Left
                drawPort(g, new Point(x + c/2 - p/2, z), Color.GREEN);
                // Out B: Nord dell'Estensione (x+1, z) -> Top-Right
                drawPort(g, new Point(x + c + c/2 - p/2, z), Color.GREEN);
            }
            case SOUTH -> {
                // Input: Nord dell'Anchor (x, z-1) -> Top-Right visivamente (per come è ruotato South)
                // Anchor a SUD è il blocco più a destra visivamente
                drawPort(g, new Point(x + c + c/2 - p/2, z), Color.BLUE);

                // Out A: Sud dell'Anchor -> Bottom-Right
                drawPort(g, new Point(x + c + c/2 - p/2, z + c - p), Color.GREEN);
                // Out B: Sud dell'Estensione -> Bottom-Left
                drawPort(g, new Point(x + c/2 - p/2, z + c - p), Color.GREEN);
            }
            case EAST -> {
                // Input: Ovest dell'Anchor -> Left-Top
                drawPort(g, new Point(x, z + c/2 - p/2), Color.BLUE);

                // Out A: Est dell'Anchor -> Right-Top
                drawPort(g, new Point(x + c - p, z + c/2 - p/2), Color.GREEN);
                // Out B: Est dell'Estensione -> Right-Bottom
                drawPort(g, new Point(x + c - p, z + c + c/2 - p/2), Color.GREEN);
            }
            case WEST -> {
                // Input: Est dell'Anchor -> Right-Bottom
                drawPort(g, new Point(x + c - p, z + c + c/2 - p/2), Color.BLUE);

                // Out A: Ovest dell'Anchor -> Left-Bottom
                drawPort(g, new Point(x, z + c + c/2 - p/2), Color.GREEN);
                // Out B: Ovest dell'Estensione -> Left-Top
                drawPort(g, new Point(x, z + c/2 - p/2), Color.GREEN);
            }
        }
    }

    private void drawPort(Graphics2D g, Point pt, Color color) {
        g.setColor(color);
        g.fillRect(pt.x, pt.y, 8, 8);
        g.setColor(Color.WHITE);
        g.drawRect(pt.x, pt.y, 8, 8);
    }

    private void drawBeltItem(Graphics2D g, ConveyorBelt belt, int x, int z) {
        JsonObject meta = belt.serialize();
        if (meta.has("currentItem")) {
            drawItemShape(g, meta.getAsJsonObject("currentItem"), x, z);
        }
    }

    private void drawSplitterItem(Graphics2D g, Splitter splitter, int x, int z) {
        JsonObject meta = splitter.serialize();
        // Lo Splitter ora usa "items" array invece di "currentItem", prendiamo il primo slot
        if (meta.has("items") && meta.getAsJsonArray("items").size() > 0) {
            var item = meta.getAsJsonArray("items").get(0);
            if (!item.isJsonNull() && item.isJsonObject()) {
                // Disegniamo l'item al centro dell'Anchor
                drawItemShape(g, item.getAsJsonObject(), x, z);
            }
        }
    }

    private void drawItemShape(Graphics2D g, JsonObject item, int x, int z) {
        String colorStr = item.has("color") ? item.get("color").getAsString() : "RAW";
        String shapeStr = (item.has("shape") && !item.get("shape").isJsonNull())
                ? item.get("shape").getAsString() : "LIQUID";

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
        if (mouseHoverPos == null || currentTool == null) return;
        Vector3Int dim = registry.getDimensions(currentTool);

        // Calcolo dimensioni per ghost
        int dimX = dim.x();
        int dimZ = dim.z();
        if (currentOrientation == Direction.EAST || currentOrientation == Direction.WEST) {
            dimX = dim.z();
            dimZ = dim.x();
        }

        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.4f));
        int x = OFFSET_X + (mouseHoverPos.x() * CELL_SIZE);
        int z = OFFSET_Y + (mouseHoverPos.z() * CELL_SIZE);

        g.setColor(getColorForType(currentTool));
        g.fillRect(x, z, dimX * CELL_SIZE, dimZ * CELL_SIZE);
        g.setColor(Color.WHITE);
        g.drawRect(x, z, dimX * CELL_SIZE, dimZ * CELL_SIZE);

        drawDirectionArrow(g, x, z, dimX * CELL_SIZE, dimZ * CELL_SIZE, currentOrientation);
        g.setComposite(AlphaComposite.SrcOver);
    }

    private void drawDirectionArrow(Graphics2D g, int x, int y, int w, int h, Direction dir) {
        g.setColor(Color.YELLOW);
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
        return switch(c) {
            case "RED" -> new Color(200, 0, 0, alpha);
            case "BLUE" -> new Color(0, 0, 200, alpha);
            case "YELLOW" -> new Color(200, 200, 0, alpha);
            case "PURPLE" -> new Color(160, 32, 240, alpha);
            case "GREEN" -> new Color(0, 200, 0, alpha);
            default -> new Color(120, 120, 120, alpha);
        };
    }

    private Color getColorForType(String type) {
        return switch (type) {
            case "drill_mk1" -> Color.LIGHT_GRAY;
            case "conveyor_belt" -> Color.DARK_GRAY;
            case "nexus_core" -> new Color(150, 0, 150);
            case "chromator" -> new Color(255, 140, 0);
            case "color_mixer" -> new Color(0, 200, 200);
            case "splitter" -> new Color(100, 149, 237); // Cornflower Blue
            default -> Color.RED;
        };
    }
}