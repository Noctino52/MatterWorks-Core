package com.matterworks.core.ui.swing.factory;

import com.matterworks.core.domain.machines.registry.BlockRegistry;
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

        // Tooltip: Swing will call getToolTipText() frequently while hovering.
        // We must keep getToolTipText() O(1) and NEVER do heavy work on EDT.
        ToolTipManager.sharedInstance().registerComponent(this);
        ToolTipManager.sharedInstance().setInitialDelay(500);
        ToolTipManager.sharedInstance().setReshowDelay(500);
        ToolTipManager.sharedInstance().setDismissDelay(60_000);

        // Required by Swing to enable dynamic tooltips
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
        // Avoid redundant switches (prevents pointless refresh/repaint bursts)
        if (uuid != null && uuid.equals(state.playerUuid)) return;
        if (uuid == null && state.playerUuid == null) return;

        state.playerUuid = uuid;
        state.clearHover();
        forceRefreshNow();
    }





    public void setLayer(int y) {
        int cap = Math.max(1, state.maxBuildHeight);
        int maxLayer = cap - 1;

        int clamped = y;
        if (clamped < 0) clamped = 0;
        if (clamped > maxLayer) clamped = maxLayer;

        if (clamped == state.currentLayer) return;

        state.currentLayer = clamped;
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
        if (event == null) return null;
        // Must be constant-time, cached-only.
        return controller.getInspectionTooltipHtml(event.getX(), event.getY());
    }


    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        renderer.paint(g, getWidth(), getHeight());
    }
}
