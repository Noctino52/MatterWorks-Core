package com.matterworks.core.ui.swing.factory;

import com.matterworks.core.common.Direction;
import com.matterworks.core.common.GridPosition;

import java.util.UUID;

final class FactoryPanelState {

    // Grid constants
    static final int GRID_SIZE = 50;   // ✅ nel tuo progetto ora è 50x50
    static final int BASE_CELL_PX = 40;

    // Zoom clamp
    static final double MIN_USER_ZOOM = 0.35;
    static final double MAX_USER_ZOOM = 3.0;

    // Padding minimo dal bordo del panel
    static final int MIN_PAD = 12;

    record Viewport(int cellPx, int offX, int offY, int gridPx) {}

    volatile UUID playerUuid;

    volatile String currentTool = "drill_mk1";
    volatile Direction currentOrientation = Direction.NORTH;
    volatile int currentLayer = 0;

    volatile GridPosition mouseHoverPos = null;

    volatile int lastHoverGX = Integer.MIN_VALUE;
    volatile int lastHoverGZ = Integer.MIN_VALUE;

    // fattore zoom controllato dall’utente (mouse wheel)
    volatile double userZoom = 1.0;

    // ✅ Camera pan (in pixel) controllato con middle-mouse drag
    private volatile int panPxX = 0;
    private volatile int panPxY = 0;

    private volatile boolean panning = false;
    private volatile int lastPanMouseX = 0;
    private volatile int lastPanMouseY = 0;

    // ultimo viewport calcolato
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

        // centro base
        int baseOffX = (w - gridPx) / 2;
        int baseOffY = (h - gridPx) / 2;

        // se grid più piccola del pannello, garantisci padding minimo
        if (gridPx <= w) baseOffX = Math.max(baseOffX, MIN_PAD);
        if (gridPx <= h) baseOffY = Math.max(baseOffY, MIN_PAD);

        // ✅ applica pan
        int offX = baseOffX + panPxX;
        int offY = baseOffY + panPxY;

        // ✅ clamp: non permettere di “perdere” completamente la griglia
        // Range ammesso: lascia almeno MIN_PAD di margine “interno” su uno dei due lati.
        int minOffX, maxOffX;
        if (gridPx <= w - 2 * MIN_PAD) {
            // griglia sta tutta: si può muovere SOLO dentro la cornice
            minOffX = MIN_PAD;
            maxOffX = w - MIN_PAD - gridPx;
        } else {
            // griglia più grande: puoi trascinare, ma non oltre MIN_PAD
            minOffX = w - MIN_PAD - gridPx;
            maxOffX = MIN_PAD;
        }
        if (minOffX > maxOffX) {
            // caso degenerato (finestre minuscole): blocca al base
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

        // Importante: se clamp “riporta indietro”, aggiorna anche il pan accumulato
        // così non cresce all’infinito fuori range.
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
