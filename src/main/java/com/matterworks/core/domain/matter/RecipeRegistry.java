package com.matterworks.core.domain.matter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class RecipeRegistry {

    private static final List<Recipe> recipes = new ArrayList<>();

    static {
        // --- DEFINIZIONE RICETTE INIZIALI ---

        // Ricetta: Dipingi di Rosso
        // Input: CUBE RAW -> Output: CUBE RED
        recipes.add(new Recipe(
                "paint_red",
                List.of(new MatterPayload(MatterShape.CUBE, MatterColor.RAW)),
                new MatterPayload(MatterShape.CUBE, MatterColor.RED),
                2.0f, // 2 secondi
                0
        ));
    }

    public static Optional<Recipe> findMatchingRecipe(List<MatterPayload> inputs) {
        // Logica semplificata: Cerca la prima ricetta che corrisponde al primo item dell'input
        // In futuro servirà un matching più complesso per input multipli (Mixer)
        if (inputs.isEmpty()) return Optional.empty();

        MatterPayload firstInput = inputs.get(0);

        return recipes.stream()
                .filter(r -> !r.inputs().isEmpty())
                .filter(r -> isMatch(r.inputs().get(0), firstInput))
                .findFirst();
    }

    private static boolean isMatch(MatterPayload required, MatterPayload actual) {
        return required.shape() == actual.shape() && required.color() == actual.color();
    }
}