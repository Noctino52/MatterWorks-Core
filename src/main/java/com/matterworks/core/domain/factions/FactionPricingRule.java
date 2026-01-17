package com.matterworks.core.domain.factions;

import com.matterworks.core.domain.matter.MatterColor;
import com.matterworks.core.domain.matter.MatterEffect;
import com.matterworks.core.domain.matter.MatterPayload;
import com.matterworks.core.domain.matter.MatterShape;

import java.util.List;

import static com.matterworks.core.domain.factions.FactionRuleEnums.*;

public record FactionPricingRule(
        long id,
        int factionId,
        Sentiment sentiment,
        MatchType matchType,
        CombineMode combineMode,
        MatterColor color,
        MatterShape shape,
        MatterEffect effect,
        double multiplier,
        int priority,
        String note
) {

    public int specificityScore() {
        int s = 0;
        if (color != null) s++;
        if (shape != null) s++;
        if (effect != null) s++;
        // EXACT is considered “more specific” than CONTAINS at same attribute count
        if (matchType == MatchType.EXACT) s += 1;
        return s;
    }

    public boolean matches(MatterPayload payload) {
        if (payload == null) return false;

        boolean hasColor = (color != null);
        boolean hasShape = (shape != null);
        boolean hasEffect = (effect != null);

        // Normalize payload values
        MatterColor pColor = payload.color();
        MatterShape pShape = payload.shape();
        List<MatterEffect> pEffects = (payload.effects() != null) ? payload.effects() : List.of();

        // If rule has no constraints, it's meaningless -> do not match.
        if (!hasColor && !hasShape && !hasEffect) return false;

        if (matchType == MatchType.EXACT) {
            // EXACT is meaningful only if it fully specifies the matter (all 3).
            // Otherwise fallback to CONTAINS/ALL semantics to avoid surprising behavior.
            if (!(hasColor && hasShape && hasEffect)) {
                return matchesContainsAll(pColor, pShape, pEffects);
            }

            boolean ok = (pColor == color)
                    && (pShape == shape)
                    && (pEffects.contains(effect));

            // If in future you allow multiple effects, you may want:
            // ok &= (pEffects.size() == 1);
            return ok;
        }

        // CONTAINS
        if (combineMode == CombineMode.ANY) {
            boolean any = false;
            if (hasColor) any |= (pColor == color);
            if (hasShape) any |= (pShape == shape);
            if (hasEffect) any |= (pEffects.contains(effect));
            return any;
        }

        // ALL
        return matchesContainsAll(pColor, pShape, pEffects);
    }

    private boolean matchesContainsAll(MatterColor pColor, MatterShape pShape, List<MatterEffect> pEffects) {
        if (color != null && pColor != color) return false;
        if (shape != null && pShape != shape) return false;
        if (effect != null && !pEffects.contains(effect)) return false;
        return true;
    }
}
