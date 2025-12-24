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

    // Padding minimo dal bordo del panel (quando grid Ã¨ piÃ¹ piccola)
    static final int MIN_PAD = 12;

    record Viewport(int cellPx, int offX, int offY, int gridPx) {}

    volatile UUID playerUuid;

    volatile String currentTool = "drill_mk1";
    volatile Direction currentOrientation = Direction.NORTH;
    volatile int currentLayer = 0;

    volatile GridPosition mouseHoverPos = null;

    volatile int lastHoverGX = Integer.MIN_VALUE;
    volatile int lastHoverGZ = Integer.MIN_VALUE;

    // fattore zoom controllato dallâ€™utente (mouse wheel)
    volatile double userZoom = 1.0;

    // ultimo viewport calcolato (renderer & controller usano lo stesso)
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

    Viewport computeViewport(int panelW, int panelH) {
        // Fit: calcolo cellPx per far stare tutto il 50x50 nel pannello
        // (cosÃ¬ non taglia e lo spazio â€œvuotoâ€ a destra si riduce/annulla)
        int w = Math.max(1, panelW);
        int h = Math.max(1, panelH);

        // spazio disponibile (teniamo un minimo bordo)
        int availW = Math.max(1, w - 2 * MIN_PAD);
        int availH = Math.max(1, h - 2 * MIN_PAD);

        double fitCellW = (double) availW / (double) GRID_SIZE;
        double fitCellH = (double) availH / (double) GRID_SIZE;

        // base "fit" in px per cella (senza userZoom)
        double fitCell = Math.floor(Math.min(fitCellW, fitCellH));
        if (fitCell < 6) fitCell = 6; // evita cell troppo piccola

        // applica userZoom
        double z = clamp(userZoom, MIN_USER_ZOOM, MAX_USER_ZOOM);
        int cellPx = (int) Math.round(fitCell * z);
        if (cellPx < 6) cellPx = 6;

        int gridPx = cellPx * GRID_SIZE;

        // Offset: centro se c'Ã¨ spazio, altrimenti negativo (zoom-in -> crop voluto)
        int offX = (w - gridPx) / 2;
        int offY = (h - gridPx) / 2;

        // se grid Ã¨ piÃ¹ piccola, garantisci un minimo padding (evita â€œattaccataâ€ ai bordi)
        if (gridPx <= w) offX = Math.max(offX, MIN_PAD);
        if (gridPx <= h) offY = Math.max(offY, MIN_PAD);

        Viewport vp = new Viewport(cellPx, offX, offY, gridPx);
        lastViewport = vp;
        return vp;
    }

    Viewport getLastViewport() {
        return lastViewport;
    }

    void applyWheelZoom(int wheelRotation) {
        // wheelRotation > 0 = scroll down -> zoom out
        // wheelRotation < 0 = scroll up -> zoom in
        double factor = Math.pow(1.10, -wheelRotation);
        userZoom = clamp(userZoom * factor, MIN_USER_ZOOM, MAX_USER_ZOOM);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}