package com.matterworks.core.infrastructure.swing;

import com.google.gson.JsonObject;
import com.matterworks.core.common.Direction;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.domain.machines.BlockRegistry;
import com.matterworks.core.domain.machines.ConveyorBelt;
import com.matterworks.core.domain.machines.NexusMachine;
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
    private final UUID playerUuid;

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
            public void mouseMoved(MouseEvent e) {
                updateMousePos(e.getX(), e.getY());
                repaint();
            }
        });
        this.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                requestFocusInWindow();
                handleMouseClick(e);
                repaint();
            }
        });
        this.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_R) rotate();
            }
        });
    }

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
        int gx = toGridX(x);
        int gz = toGridY(y);
        if (gx >= 0 && gx <= 20 && gz >= 0 && gz <= 20) {
            this.mouseHoverPos = new GridPosition(gx, currentLayer, gz);
        } else {
            this.mouseHoverPos = null;
        }
    }

    private void handleMouseClick(MouseEvent e) {
        if (mouseHoverPos == null) return;
        if (SwingUtilities.isLeftMouseButton(e)) {
            if (currentTool != null) {
                gridManager.placeMachine(playerUuid, mouseHoverPos, currentTool, currentOrientation);
            }
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
            int x = toScreenX(pos.x());
            int z = toScreenY(pos.z());

            Color c = switch(type) {
                case RAW -> new Color(100, 100, 100, 100);
                case RED -> new Color(200, 0, 0, 100);
                case BLUE -> new Color(0, 0, 200, 100);
                case YELLOW -> new Color(200, 200, 0, 100);
                default -> new Color(255, 255, 255, 50);
            };
            g.setColor(c);
            g.fillRect(x, z, CELL_SIZE, CELL_SIZE);
            g.setColor(c.darker());
            g.drawRect(x, z, CELL_SIZE, CELL_SIZE);
            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.PLAIN, 9));
            g.drawString(type.name(), x + 2, z + 12);
        }
    }

    private void drawGrid(Graphics2D g) {
        g.setColor(new Color(50, 50, 50));
        for (int i = 0; i <= 20; i++) {
            g.drawLine(toScreenX(i), toScreenY(0), toScreenX(i), toScreenY(20));
            g.drawLine(toScreenX(0), toScreenY(i), toScreenX(20), toScreenY(i));
        }
        g.setColor(new Color(60, 60, 60));
        g.drawString("LAYER Y = " + currentLayer, 10, getHeight() - 10);
    }

    private void drawMachines(Graphics2D g) {
        Map<GridPosition, PlacedMachine> machines = gridManager.getSnapshot(playerUuid);
        for (PlacedMachine m : machines.values()) {
            int machineY = m.getPos().y();
            int machineHeight = m.getDimensions().y();
            if (currentLayer >= machineY && currentLayer < machineY + machineHeight) {
                drawSingleMachine(g, m, false);
            }
        }
    }

    private void drawGhost(Graphics2D g) {
        if (mouseHoverPos == null || currentTool == null) return;
        Vector3Int dimBase = registry.getDimensions(currentTool);

        boolean isRotated = (currentOrientation == Direction.EAST || currentOrientation == Direction.WEST);
        int w = (isRotated ? dimBase.z() : dimBase.x()) * CELL_SIZE;
        int h = (isRotated ? dimBase.x() : dimBase.z()) * CELL_SIZE;

        Composite original = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));

        int x = toScreenX(mouseHoverPos.x());
        int z = toScreenY(mouseHoverPos.z());

        g.setColor(getColorForType(currentTool));
        g.fillRect(x, z, w, h);
        g.setColor(Color.WHITE);
        g.drawRect(x, z, w, h);

        drawDirectionArrow(g, x, z, w, h, currentOrientation);
        g.setComposite(original);
    }

    private void drawSingleMachine(Graphics2D g, PlacedMachine m, boolean isGhost) {
        GridPosition pos = m.getPos();
        Vector3Int dim = m.getDimensions();

        int x = toScreenX(pos.x());
        int z = toScreenY(pos.z());
        int w = dim.x() * CELL_SIZE;
        int h = dim.z() * CELL_SIZE;

        g.setColor(getColorForType(m.getTypeId()));
        g.fillRect(x + 2, z + 2, w - 4, h - 4);

        // --- LOGICA PORTE SPECIFICA ---
        if (m.getTypeId().equals("nexus_core")) {
            drawNexusPorts(g, m, x, z, w, h);
        }
        else if (m.getTypeId().equals("chromator") || m.getTypeId().equals("color_mixer")) {
            drawPorts(g, m, x, z, w, h);
        } else {
            drawStandardPorts(g, m, x, z, w, h);
        }

        drawDirectionArrow(g, x, z, w, h, m.getOrientation());

        if (m instanceof ConveyorBelt belt) {
            drawBeltItem(g, belt, x, z);
        } else if (m instanceof NexusMachine) {
            g.setColor(Color.WHITE);
            if (pos.y() == currentLayer) g.drawString("NEXUS", x + 10, z + 20);
        }
    }

    // --- NUOVO METODO: PORTE DEL NEXUS ---
    // Disegna porte di ingresso (BLU) su tutti i lati per i primi 2 livelli.
    private void drawNexusPorts(Graphics2D g, PlacedMachine m, int x, int z, int w, int h) {
        int machineBaseY = m.getPos().y();
        int relativeY = currentLayer - machineBaseY;

        // Se siamo al livello 0 o 1 del Nexus (base o centro), disegna porte
        if (relativeY >= 0 && relativeY <= 1) {
            g.setColor(Color.BLUE);
            int pSize = 8;

            // Porta Nord
            g.fillRect(x + w/2 - pSize/2, z, pSize, pSize);
            // Porta Sud
            g.fillRect(x + w/2 - pSize/2, z + h - pSize, pSize, pSize);
            // Porta Ovest
            g.fillRect(x, z + h/2 - pSize/2, pSize, pSize);
            // Porta Est
            g.fillRect(x + w - pSize, z + h/2 - pSize/2, pSize, pSize);
        }
        // Nessun Output (Verde)
    }

    private void drawBeltItem(Graphics2D g, ConveyorBelt belt, int x, int z) {
        JsonObject meta = belt.serialize();
        if (meta.has("currentItem")) {
            JsonObject itemJson = meta.getAsJsonObject("currentItem");
            String shapeStr = null;
            String colorStr = "RAW";

            try {
                if (itemJson.has("shape") && !itemJson.get("shape").isJsonNull()) {
                    shapeStr = itemJson.get("shape").getAsString();
                }
                if (itemJson.has("color")) colorStr = itemJson.get("color").getAsString();
            } catch(Exception ignored) {}

            Color itemColor = switch(colorStr) {
                case "RAW" -> new Color(180, 180, 180);
                case "RED" -> new Color(220, 50, 50);
                case "BLUE" -> new Color(50, 80, 255);
                case "YELLOW" -> new Color(255, 220, 0);
                case "GREEN" -> new Color(50, 200, 50);
                case "ORANGE" -> new Color(255, 140, 0);
                case "PURPLE" -> new Color(160, 32, 240);
                default -> Color.MAGENTA;
            };

            int itemSize = 20;
            int ix = x + (CELL_SIZE - itemSize) / 2;
            int iz = z + (CELL_SIZE - itemSize) / 2;

            g.setColor(itemColor);

            if ("CUBE".equals(shapeStr)) {
                g.fillRect(ix, iz, itemSize, itemSize);
                g.setColor(Color.WHITE);
                g.drawRect(ix, iz, itemSize, itemSize);
            } else {
                g.fillOval(ix, iz, itemSize, itemSize);
                g.setColor(Color.WHITE);
                g.drawOval(ix, iz, itemSize, itemSize);
            }
        }
    }

    private void drawPorts(Graphics2D g, PlacedMachine m, int x, int z, int w, int h) {
        int pSize = 8;
        Direction dir = m.getOrientation();
        Point out = null, in1 = null, in2 = null;

        // Dimensioni macchina in pixel
        // w e h sono già ruotati (es. NORTH: w=2 celle, h=1 cella)

        int cell = CELL_SIZE;

        switch (dir) {
            case NORTH:
                // Out: Blocco Sinistro (0,0) -> Lato Nord
                out = new Point(x + cell/2 - pSize/2, z);
                // In: Lato Sud (z+h)
                in1 = new Point(x + cell/2 - pSize/2, z + h - pSize); // Blocco Sinistro
                in2 = new Point(x + (cell*3)/2 - pSize/2, z + h - pSize); // Blocco Destro
                break;

            case SOUTH:
                // Out: Blocco Sinistro (Guardando Sud, è a dx nello schermo) -> (1,0) -> Lato Sud
                out = new Point(x + (cell*3)/2 - pSize/2, z + h - pSize);
                // In: Lato Nord
                in1 = new Point(x + (cell*3)/2 - pSize/2, z);
                in2 = new Point(x + cell/2 - pSize/2, z);
                break;

            case EAST:
                // Out: Blocco Sinistro (Guardando Est, è sopra) -> (0,0) -> Lato Est
                out = new Point(x + w - pSize, z + cell/2 - pSize/2);
                // In: Lato Ovest
                in1 = new Point(x, z + cell/2 - pSize/2);
                in2 = new Point(x, z + (cell*3)/2 - pSize/2);
                break;

            case WEST:
                // Out: Blocco Sinistro (Guardando Ovest, è sotto) -> (0,1) -> Lato Ovest
                out = new Point(x, z + (cell*3)/2 - pSize/2);
                // In: Lato Est
                in1 = new Point(x + w - pSize, z + (cell*3)/2 - pSize/2);
                in2 = new Point(x + w - pSize, z + cell/2 - pSize/2);
                break;
        }

        if (out != null) {
            g.setColor(Color.GREEN); g.fillRect(out.x, out.y, pSize, pSize);
            g.setColor(Color.BLUE);  g.fillRect(in1.x, in1.y, pSize, pSize);
            g.setColor(Color.BLUE);  g.fillRect(in2.x, in2.y, pSize, pSize);
        }
    }

    private void drawStandardPorts(Graphics2D g, PlacedMachine m, int x, int z, int w, int h) {
        int pSize = 8;
        Point out = null, in = null;
        switch (m.getOrientation()) {
            case NORTH -> { out = new Point(x + w/2 - pSize/2, z); in = new Point(x + w/2 - pSize/2, z + h - pSize); }
            case SOUTH -> { out = new Point(x + w/2 - pSize/2, z + h - pSize); in = new Point(x + w/2 - pSize/2, z); }
            case EAST  -> { out = new Point(x + w - pSize, z + h/2 - pSize/2); in = new Point(x, z + h/2 - pSize/2); }
            case WEST  -> { out = new Point(x, z + h/2 - pSize/2); in = new Point(x + w - pSize, z + h/2 - pSize/2); }
        }
        if (out != null) { g.setColor(Color.GREEN); g.fillRect(out.x, out.y, pSize, pSize); }
        if (in != null && !m.getTypeId().equals("drill_mk1")) { g.setColor(Color.BLUE); g.fillRect(in.x, in.y, pSize, pSize); }
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

    private void drawDirectionArrow(Graphics2D g, int x, int y, int w, int h, Direction dir) {
        g.setColor(Color.YELLOW);
        int cx = x + w / 2;
        int cy = y + h / 2;
        switch (dir) {
            case NORTH -> g.fillOval(cx - 3, y + 2, 6, 6);
            case SOUTH -> g.fillOval(cx - 3, y + h - 8, 6, 6);
            case EAST ->  g.fillOval(x + w - 8, cy - 3, 6, 6);
            case WEST ->  g.fillOval(x + 2, cy - 3, 6, 6);
        }
    }

    private int toScreenX(int gridX) { return OFFSET_X + (gridX * CELL_SIZE); }
    private int toScreenY(int gridZ) { return OFFSET_Y + (gridZ * CELL_SIZE); }
    private int toGridX(int screenX) { return (screenX - OFFSET_X) / CELL_SIZE; }
    private int toGridY(int screenY) { return (screenY - OFFSET_Y) / CELL_SIZE; }
}