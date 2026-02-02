package com.matterworks.core.ui.swing.factory;

import com.matterworks.core.common.Direction;
import com.matterworks.core.common.GridPosition;

import java.util.UUID;

final class FactoryPanelState {

    // Grid constants
    static final int GRID_SIZE = 50;
    static final int BASE_CELL_PX = 40;

    // Zoom clamp
    static final double MIN_USER_ZOOM = 0.35;
    static final double MAX_USER_ZOOM = 3.0;

    // Minimum padding from panel border
    static final int MIN_PAD = 12;

    record Viewport(int cellPx, int offX, int offY, int gridPx) {}

    volatile UUID playerUuid;

    // Hard rename: default tool is now "drill"
    volatile String currentTool = "drill";
    volatile Direction currentOrientation = Direction.NORTH;
    volatile int currentLayer = 0;

    volatile int maxBuildHeight = 4;

    volatile GridPosition mouseHoverPos = null;

    volatile int lastHoverGX = Integer.MIN_VALUE;
    volatile int lastHoverGZ = Integer.MIN_VALUE;

    // User-controlled zoom factor (mouse wheel)
    volatile double userZoom = 1.0;

    // Camera pan (pixels), controlled by middle-mouse dragging
    private volatile int panPxX = 0;
    private volatile int panPxY = 0;

    private volatile boolean panning = false;
    private volatile int lastPanMouseX = 0;
    private volatile int lastPanMouseY = 0;

    // Last computed viewport
    private volatile Viewport lastViewport = new Viewport(BASE_CELL_PX, 50, 50, BASE_CELL_PX * GRID_SIZE);

    void rotate() {
        switch (currentOrientation) {
            case NORTH -> currentOrientation = Direction.EAST;
            case EAST -> currentOrientation = Direction.SOUTH;
            case SOUTH -> currentOrientation = Direction.WEST;
            case WEST -> currentOrientation = Direction.NORTH;
        }
    }

    void clearHover() {
        mouseHoverPos = null;
        lastHoverGX = Integer.MIN_VALUE;
        lastHoverGZ = Integer.MIN_VALUE;
    }

    boolean isPanning() {
        return panning;
    }

    void beginPan(int mouseX, int mouseY) {
        panning = true;
        lastPanMouseX = mouseX;
        lastPanMouseY = mouseY;
    }

    void updatePan(int mouseX, int mouseY) {
        if (!panning) return;

        int dx = mouseX - lastPanMouseX;
        int dy = mouseY - lastPanMouseY;

        lastPanMouseX = mouseX;
        lastPanMouseY = mouseY;

        panPxX += dx;
        panPxY += dy;
    }

    void endPan() {
        panning = false;
    }

    void resetCamera() {
        userZoom = 1.0;
        panPxX = 0;
        panPxY = 0;
        panning = false;
    }

    Viewport computeViewport(int panelW, int panelH) {
        int w = Math.max(1, panelW);
        int h = Math.max(1, panelH);

        int availW = Math.max(1, w - 2 * MIN_PAD);
        int availH = Math.max(1, h - 2 * MIN_PAD);

        double fitCellW = (double) availW / (double) GRID_SIZE;
        double fitCellH = (double) availH / (double) GRID_SIZE;

        double fitCell = Math.floor(Math.min(fitCellW, fitCellH));
        if (fitCell < 6) fitCell = 6;

        double z = clamp(userZoom, MIN_USER_ZOOM, MAX_USER_ZOOM);
        int cellPx = (int) Math.round(fitCell * z);
        if (cellPx < 6) cellPx = 6;

        int gridPx = cellPx * GRID_SIZE;

        // Base centered offsets
        int baseOffX = (w - gridPx) / 2;
        int baseOffY = (h - gridPx) / 2;

        // If the grid fits inside the panel, keep minimum padding
        if (gridPx <= w) baseOffX = Math.max(baseOffX, MIN_PAD);
        if (gridPx <= h) baseOffY = Math.max(baseOffY, MIN_PAD);

        // Apply pan
        int offX = baseOffX + panPxX;
        int offY = baseOffY + panPxY;

        // Clamp: do not allow the grid to completely leave the view
        int minOffX, maxOffX;
        if (gridPx <= w - 2 * MIN_PAD) {
            // Grid fully fits: keep it inside the frame
            minOffX = MIN_PAD;
            maxOffX = w - MIN_PAD - gridPx;
        } else {
            // Grid larger: allow dragging, but keep at least MIN_PAD visible
            minOffX = w - MIN_PAD - gridPx;
            maxOffX = MIN_PAD;
        }
        if (minOffX > maxOffX) {
            minOffX = maxOffX = baseOffX;
        }
        offX = clampInt(offX, minOffX, maxOffX);

        int minOffY, maxOffY;
        if (gridPx <= h - 2 * MIN_PAD) {
            minOffY = MIN_PAD;
            maxOffY = h - MIN_PAD - gridPx;
        } else {
            minOffY = h - MIN_PAD - gridPx;
            maxOffY = MIN_PAD;
        }
        if (minOffY > maxOffY) {
            minOffY = maxOffY = baseOffY;
        }
        offY = clampInt(offY, minOffY, maxOffY);

        // If clamping pulled it back, update stored pan so it doesn't grow unbounded
        panPxX = offX - baseOffX;
        panPxY = offY - baseOffY;

        Viewport vp = new Viewport(cellPx, offX, offY, gridPx);
        lastViewport = vp;
        return vp;
    }

    Viewport getLastViewport() {
        return lastViewport;
    }

    void applyWheelZoom(int wheelRotation) {
        double factor = Math.pow(1.10, -wheelRotation);
        userZoom = clamp(userZoom * factor, MIN_USER_ZOOM, MAX_USER_ZOOM);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
