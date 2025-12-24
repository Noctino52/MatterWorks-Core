package com.matterworks.core.domain.matter;

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
     * Logica di miscelazione RYB (game-standard):
     * - Primario + Primario => Secondario
     * - Secondario + Primario => WHITE (raffinazione finale richiesta dal design)
     * - Qualsiasi combinazione non prevista => WHITE (mai RAW come output)
     *
     * RAW è un "materiale grezzo" (senso solo come input/risorsa base), non un risultato di mixing.
     */
    public static MatterColor mix(MatterColor c1, MatterColor c2) {
        if (c1 == null && c2 == null) return RAW;
        if (c1 == null) return c2;
        if (c2 == null) return c1;

        if (c1 == WHITE || c2 == WHITE) return WHITE;

        // RAW non deve essere un risultato di mixing. Se capita come input, “passa” l’altro colore.
        if (c1 == RAW && c2 == RAW) return RAW;
        if (c1 == RAW) return c2;
        if (c2 == RAW) return c1;

        if (c1 == c2) return c1;

        // Primari
        if (isMix(c1, c2, RED, YELLOW)) return ORANGE;
        if (isMix(c1, c2, YELLOW, BLUE)) return GREEN;
        if (isMix(c1, c2, RED, BLUE)) return PURPLE;

        // Se almeno uno è secondario e l'altro è primario => WHITE (richiesta)
        if ((isPrimary(c1) && isSecondary(c2)) || (isPrimary(c2) && isSecondary(c1))) return WHITE;

        // Secondario + Secondario (o altre combinazioni) => WHITE (mai RAW)
        return WHITE;
    }

    private static boolean isMix(MatterColor in1, MatterColor in2, MatterColor target1, MatterColor target2) {
        return (in1 == target1 && in2 == target2) || (in1 == target2 && in2 == target1);
    }

    private static boolean isPrimary(MatterColor c) {
        return c == RED || c == BLUE || c == YELLOW;
    }

    private static boolean isSecondary(MatterColor c) {
        return c == GREEN || c == ORANGE || c == PURPLE;
    }
}
