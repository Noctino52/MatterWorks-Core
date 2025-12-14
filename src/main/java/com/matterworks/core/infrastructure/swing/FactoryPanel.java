package com.matterworks.core.infrastructure.swing;

import com.google.gson.JsonObject;
import com.matterworks.core.common.Direction;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.common.Vector3Int;
import com.matterworks.core.domain.machines.BlockRegistry;
import com.matterworks.core.domain.machines.ConveyorBelt;
import com.matterworks.core.domain.machines.NexusMachine;
import com.matterworks.core.domain.machines.PlacedMachine;
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

    // Stato Editor
    private String currentTool = "drill_mk1";
    private Direction currentOrientation = Direction.NORTH;
    private int currentLayer = 64; // Asse Y (Default livello del terreno)

    private GridPosition mouseHoverPos = null;

    // Callback per aggiornare la GUI principale (es. quando ruoto col tasto R)
    private Runnable onStateChange;

    public FactoryPanel(GridManager gridManager, BlockRegistry registry, UUID playerUuid, Runnable onStateChange) {
        this.gridManager = gridManager;
        this.registry = registry;
        this.playerUuid = playerUuid;
        this.onStateChange = onStateChange;

        this.setBackground(new Color(30, 30, 30));
        this.setFocusable(true);

        // Mouse Handlers
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

        // Tasti Rapidi (Opzionali, ma comodi da tenere insieme ai pulsanti)
        this.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_R) rotate();
            }
        });
    }

    // --- LOGICA LAYER & COORDINATE ---

    public void setLayer(int y) {
        this.currentLayer = y;
        repaint();
    }

    public int getCurrentLayer() { return currentLayer; }

    private void updateMousePos(int x, int y) {
        int gx = toGridX(x);
        int gz = toGridY(y);
        if (gx >= 0 && gx <= 20 && gz >= 0 && gz <= 20) {
            // FIX: Usiamo currentLayer invece di 64 fisso
            this.mouseHoverPos = new GridPosition(gx, currentLayer, gz);
        } else {
            this.mouseHoverPos = null;
        }
    }

    private void handleMouseClick(MouseEvent e) {
        if (mouseHoverPos == null) return;

        if (SwingUtilities.isLeftMouseButton(e)) {
            if (currentTool != null) {
                System.out.println("🔨 Place " + currentTool + " at " + mouseHoverPos);
                boolean success = gridManager.placeMachine(playerUuid, mouseHoverPos, currentTool);
                if (success) {
                    PlacedMachine pm = gridManager.getMachineAt(mouseHoverPos);
                    if (pm != null) pm.setOrientation(currentOrientation);
                }
            }
        } else if (SwingUtilities.isRightMouseButton(e)) {
            System.out.println("🔥 Remove at " + mouseHoverPos);
            gridManager.removeComponent(mouseHoverPos);
        }
    }

    // --- COMANDI ESTERNI (Chiamati dalla GUI) ---
    public void setTool(String toolId) {
        this.currentTool = toolId;
        repaint();
    }

    public void rotate() {
        // Rotazione
        switch(currentOrientation) {
            case NORTH -> currentOrientation = Direction.EAST;
            case EAST -> currentOrientation = Direction.SOUTH;
            case SOUTH -> currentOrientation = Direction.WEST;
            case WEST -> currentOrientation = Direction.NORTH;
        }
        if (onStateChange != null) onStateChange.run(); // Aggiorna etichette GUI
        repaint();
    }

    public String getCurrentToolName() { return currentTool != null ? currentTool : "None"; }
    public String getCurrentOrientationName() { return currentOrientation.name(); }

    // --- RENDERING ---
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        drawGrid(g2);
        drawMachines(g2); // Disegna solo macchine su questo layer
        drawGhost(g2);
    }

    private void drawGrid(Graphics2D g) {
        g.setColor(new Color(50, 50, 50));
        for (int i = 0; i <= 20; i++) {
            g.drawLine(toScreenX(i), toScreenY(0), toScreenX(i), toScreenY(20));
            g.drawLine(toScreenX(0), toScreenY(i), toScreenX(20), toScreenY(i));
        }

        // Indicatore del Layer corrente sullo sfondo
        g.setColor(new Color(60, 60, 60));
        g.drawString("LAYER Y = " + currentLayer, 10, getHeight() - 10);
    }

    private void drawMachines(Graphics2D g) {
        Map<GridPosition, PlacedMachine> machines = gridManager.getSnapshot();

        for (PlacedMachine m : machines.values()) {
            // LOGICA DI VISIBILITÀ 3D
            // Una macchina è visibile se il layer corrente "taglia" la sua altezza.
            int machineY = m.getPos().y();
            int machineHeight = m.getDimensions().y();

            // Se il layer corrente è dentro il range [Y, Y + H]
            if (currentLayer >= machineY && currentLayer < machineY + machineHeight) {
                drawSingleMachine(g, m, false);
            }
        }
    }

    private void drawGhost(Graphics2D g) {
        if (mouseHoverPos == null || currentTool == null) return;

        Vector3Int dim = registry.getDimensions(currentTool);

        Composite original = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));

        int x = toScreenX(mouseHoverPos.x());
        int z = toScreenY(mouseHoverPos.z());
        int w = dim.x() * CELL_SIZE;
        int h = dim.z() * CELL_SIZE;

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

        drawDirectionArrow(g, x, z, w, h, m.getOrientation());

        // Dettagli Extra
        if (m instanceof ConveyorBelt belt) {
            JsonObject meta = belt.serialize();
            if (meta.has("currentItem")) {
                g.setColor(Color.GREEN);
                g.fillOval(x + 10, z + 10, 20, 20);
            }
        } else if (m instanceof NexusMachine) {
            g.setColor(Color.WHITE);
            // Se stiamo vedendo il layer base, scriviamo il nome
            if (pos.y() == currentLayer) {
                g.drawString("NEXUS", x + 10, z + 20);
            } else {
                g.drawString("NEXUS (Part)", x + 2, z + 20);
            }
        }
    }

    private Color getColorForType(String type) {
        return switch (type) {
            case "drill_mk1" -> Color.LIGHT_GRAY;
            case "conveyor_belt" -> Color.DARK_GRAY;
            case "nexus_core" -> new Color(150, 0, 150);
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