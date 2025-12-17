package com.matterworks.core.domain.matter;

import java.util.Set;

public enum MatterColor {
    RAW,
    RED,
    BLUE,
    YELLOW,
    GREEN,
    ORANGE,
    PURPLE,
    WHITE;

    /**
     * Logica di miscelazione colori additiva (RYB standard da gioco).
     */
    public static MatterColor mix(MatterColor c1, MatterColor c2) {
        if (c1 == c2) return c1; // Rosso + Rosso = Rosso

        // Ordiniamo i colori per semplificare i check (usando Set o confronto stringhe)
        if (isMix(c1, c2, RED, YELLOW)) return ORANGE;
        if (isMix(c1, c2, YELLOW, BLUE)) return GREEN;
        if (isMix(c1, c2, RED, BLUE)) return PURPLE;

        // Fallback: se mischio cose strane (es. Viola + Verde) diventa RAW o GRIGIO SCURO
        return RAW;
    }

    private static boolean isMix(MatterColor in1, MatterColor in2, MatterColor target1, MatterColor target2) {
        return (in1 == target1 && in2 == target2) || (in1 == target2 && in2 == target1);
    }
}