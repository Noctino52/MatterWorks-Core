package com.matterworks.core.infrastructure.swing;

import com.matterworks.core.domain.machines.BlockRegistry;
import com.matterworks.core.managers.GridManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.UUID;

public class FactoryPanel extends JPanel {

    private final FactoryPanelState state;
    private final FactoryPanelController controller;
    private final FactoryPanelRenderer renderer;

    public FactoryPanel(GridManager gridManager,
                        BlockRegistry registry,
                        UUID playerUuid,
                        Runnable onStateChange,
                        Runnable onEconomyMaybeChanged) {

        this.state = new FactoryPanelState();
        this.state.playerUuid = playerUuid;

        this.controller = new FactoryPanelController(this, gridManager, state, onEconomyMaybeChanged);
        this.renderer = new FactoryPanelRenderer(registry, controller, state);

        setBackground(new Color(30, 30, 30));
        setFocusable(true);
        setDoubleBuffered(true);

        // ✅ abilita tooltip e rendilo "immediato" (infobox hover)
        ToolTipManager.sharedInstance().registerComponent(this);
        ToolTipManager.sharedInstance().setInitialDelay(0);
        ToolTipManager.sharedInstance().setReshowDelay(0);
        ToolTipManager.sharedInstance().setDismissDelay(60_000);

        // serve per "accendere" i tooltip su Swing (poi getToolTipText viene chiamato dinamicamente)
        setToolTipText(" ");
    }

    public void dispose() {
        controller.dispose();
    }

    public void forceRefreshNow() {
        renderer.invalidateAllCaches();
        controller.forceRefreshNow();
    }

    public void setPlayerUuid(UUID uuid) {
        state.playerUuid = uuid;
        state.clearHover();
        forceRefreshNow();
    }

    public void setLayer(int y) {
        state.currentLayer = y;
        state.clearHover();
        forceRefreshNow();
    }

    public int getCurrentLayer() {
        return state.currentLayer;
    }

    public void setTool(String toolId) {
        state.currentTool = toolId;
        renderer.invalidateGhostCache();
        repaint();
    }

    public String getCurrentToolName() {
        return state.currentTool != null ? state.currentTool : "None";
    }

    public void rotate() {
        state.rotate();
        renderer.invalidateGhostCache();
        repaint();
    }

    public String getCurrentOrientationName() {
        return state.currentOrientation.name();
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        // ✅ tooltip dinamico basato su MachineInspector/MachineInspectionInfo
        if (event == null) return null;
        return controller.getInspectionTooltipHtml(event.getX(), event.getY());
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        renderer.paint(g, getWidth(), getHeight());
    }
}
