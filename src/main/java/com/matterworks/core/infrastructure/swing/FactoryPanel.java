package com.matterworks.core.infrastructure.swing;

import com.google.gson.JsonObject;
import com.matterworks.core.common.Direction;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.domain.machines.BlockRegistry;
import com.matterworks.core.domain.machines.ConveyorBelt;
import com.matterworks.core.domain.machines.PlacedMachine;
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
    public void setTool(String toolId) { this.currentTool = toolId; repaint(); }
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
        int w = m.getDimensions().x() * CELL_SIZE;
        int h = m.getDimensions().z() * CELL_SIZE;

        g.setColor(getColorForType(m.getTypeId()));
        g.fillRect(x + 2, z + 2, w - 4, h - 4);

        if (m.getTypeId().equals("nexus_core")) {
            drawNexusPorts(g, m, x, z, w, h);
        } else if (m.getTypeId().equals("chromator") || m.getTypeId().equals("color_mixer")) {
            drawProcessorPorts(g, m, x, z, w, h);
        } else {
            drawStandardPorts(g, m, x, z, w, h);
        }

        if (m instanceof ConveyorBelt belt) drawBeltItem(g, belt, x, z);
        drawDirectionArrow(g, x, z, w, h, m.getOrientation());
    }

    private void drawStandardPorts(Graphics2D g, PlacedMachine m, int x, int z, int w, int h) {
        int p = 8;
        Point out = null, in = null;
        switch (m.getOrientation()) {
            case NORTH -> { out = new Point(x + w/2 - p/2, z); in = new Point(x + w/2 - p/2, z + h - p); }
            case SOUTH -> { out = new Point(x + w/2 - p/2, z + h - p); in = new Point(x + w/2 - p/2, z); }
            case EAST  -> { out = new Point(x + w - p, z + h/2 - p/2); in = new Point(x, z + h/2 - p/2); }
            case WEST  -> { out = new Point(x, z + h/2 - p/2); in = new Point(x + w - p, z + h/2 - p/2); }
        }
        if (out != null) drawPort(g, out, Color.GREEN);
        if (in != null && !m.getTypeId().equals("drill_mk1")) drawPort(g, in, Color.BLUE);
    }

    private void drawNexusPorts(Graphics2D g, PlacedMachine m, int x, int z, int w, int h) {
        if (currentLayer - m.getPos().y() <= 1) {
            int p = 8;
            drawPort(g, new Point(x + w/2 - p/2, z), Color.BLUE);
            drawPort(g, new Point(x + w/2 - p/2, z + h - p), Color.BLUE);
            drawPort(g, new Point(x, z + h/2 - p/2), Color.BLUE);
            drawPort(g, new Point(x + w - p, z + h/2 - p/2), Color.BLUE);
        }
    }

    private void drawProcessorPorts(Graphics2D g, PlacedMachine m, int x, int z, int w, int h) {
        int p = 8; int c = CELL_SIZE;
        boolean isChroma = m.getTypeId().equals("chromator");
        Color colS0 = isChroma ? Color.CYAN : Color.BLUE; // S0: Material/Input1
        Color colS1 = isChroma ? Color.MAGENTA : Color.BLUE; // S1: Color/Input2

        switch (m.getOrientation()) {
            case NORTH -> {
                drawPort(g, new Point(x + c/2 - p/2, z), Color.GREEN); // Out
                drawPort(g, new Point(x + c/2 - p/2, z + h - p), colS0); // In 0
                drawPort(g, new Point(x + (c*3)/2 - p/2, z + h - p), colS1); // In 1
            }
            case SOUTH -> {
                drawPort(g, new Point(x + (c*3)/2 - p/2, z + h - p), Color.GREEN);
                drawPort(g, new Point(x + (c*3)/2 - p/2, z), colS0);
                drawPort(g, new Point(x + c/2 - p/2, z), colS1);
            }
            case EAST -> {
                drawPort(g, new Point(x + w - p, z + c/2 - p/2), Color.GREEN);
                drawPort(g, new Point(x, z + c/2 - p/2), colS0);
                drawPort(g, new Point(x, z + (c*3)/2 - p/2), colS1);
            }
            case WEST -> {
                drawPort(g, new Point(x, z + (c*3)/2 - p/2), Color.GREEN);
                drawPort(g, new Point(x + w - p, z + (c*3)/2 - p/2), colS0);
                drawPort(g, new Point(x + w - p, z + c/2 - p/2), colS1);
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
            JsonObject item = meta.getAsJsonObject("currentItem");
            String colorStr = item.has("color") ? item.get("color").getAsString() : "RAW";
            String shapeStr = (item.has("shape") && !item.get("shape").isJsonNull()) ? item.get("shape").getAsString() : "LIQUID";
            g.setColor(getColorFromStr(colorStr, 255));
            int size = 18; int pad = (CELL_SIZE - size) / 2;
            if ("CUBE".equals(shapeStr)) g.fillRect(x + pad, z + pad, size, size);
            else g.fillOval(x + pad, z + pad, size, size);
            g.setColor(Color.WHITE); g.drawRect(x + pad, z + pad, size, size);
        }
    }

    private void drawGhost(Graphics2D g) {
        if (mouseHoverPos == null || currentTool == null) return;
        Vector3Int dim = registry.getDimensions(currentTool);
        if (currentOrientation == Direction.EAST || currentOrientation == Direction.WEST) {
            dim = new Vector3Int(dim.z(), dim.y(), dim.x());
        }
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.4f));
        int x = OFFSET_X + (mouseHoverPos.x() * CELL_SIZE);
        int z = OFFSET_Y + (mouseHoverPos.z() * CELL_SIZE);
        g.setColor(getColorForType(currentTool));
        g.fillRect(x, z, dim.x() * CELL_SIZE, dim.z() * CELL_SIZE);
        g.setColor(Color.WHITE);
        g.drawRect(x, z, dim.x() * CELL_SIZE, dim.z() * CELL_SIZE);
        drawDirectionArrow(g, x, z, dim.x() * CELL_SIZE, dim.z() * CELL_SIZE, currentOrientation);
        g.setComposite(AlphaComposite.SrcOver);
    }

    private void drawDirectionArrow(Graphics2D g, int x, int y, int w, int h, Direction dir) {
        g.setColor(Color.YELLOW);
        int cx = x + w / 2; int cy = y + h / 2;
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
            default -> Color.RED;
        };
    }
}