package com.matterworks.core.infrastructure.swing;

import com.matterworks.core.domain.machines.BlockRegistry; // ✅ FIX
import com.matterworks.core.managers.GridManager;

import javax.swing.*;
import java.awt.*;
import java.util.UUID;

public class FactoryPanel extends JPanel {

    private final BlockRegistry registry;
    private final FactoryPanelState state;
    private final FactoryPanelController controller;
    private final FactoryPanelRenderer renderer;

    public FactoryPanel(GridManager gridManager,
                        BlockRegistry registry,
                        UUID playerUuid,
                        Runnable onStateChange,
                        Runnable onEconomyMaybeChanged) {

        this.registry = registry;

        this.state = new FactoryPanelState();
        this.state.playerUuid = playerUuid;

        this.controller = new FactoryPanelController(this, gridManager, state, onEconomyMaybeChanged);
        this.renderer = new FactoryPanelRenderer(registry, controller, state);

        setBackground(new Color(30, 30, 30));
    }

    public void dispose() {
        controller.dispose();
    }

    public void forceRefreshNow() {
        renderer.invalidateGhostCache();
        controller.forceRefreshNow();
    }

    @Override
    public Dimension getPreferredSize() {
        int w = FactoryPanelRenderer.OFFSET_X + FactoryPanelRenderer.GRID_SIZE * FactoryPanelRenderer.CELL_SIZE + FactoryPanelRenderer.OFFSET_X;
        int h = FactoryPanelRenderer.OFFSET_Y + FactoryPanelRenderer.GRID_SIZE * FactoryPanelRenderer.CELL_SIZE + FactoryPanelRenderer.OFFSET_Y;
        return new Dimension(w, h);
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
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        renderer.paint(g, getWidth(), getHeight());
    }
}
