package com.matterworks.core.domain.telemetry.production;

import com.matterworks.core.domain.matter.MatterColor;
import com.matterworks.core.domain.matter.MatterEffect;
import com.matterworks.core.domain.matter.MatterPayload;
import com.matterworks.core.domain.matter.MatterShape;

import java.util.List;

/**
 * Stable string keys for telemetry.
 *
 * COLOR key example:
 *   C:RED
 *
 * FULL MATTER key example:
 *   M:CUBE:RED:BLAZING+SHINY
 *   M:LIQUID:RAW:NO_EFFECT
 *
 * PERFORMANCE:
 * - Called in record*() hot path.
 * - This implementation is allocation-light:
 *   - No list copy
 *   - No sorting
 *   - No streams/collectors
 *   - Precomputed strings for all (shape,color,effectsMask) combinations
 */
public final class MatterTelemetryKeys {

    private static final String SHAPE_LIQUID = "LIQUID";
    private static final String NO_EFFECT = "NO_EFFECT";

    // We want deterministic order (previous code sorted by name); with current enum:
    // SHINY, BLAZING, GLITCH -> alphabetical is BLAZING, GLITCH, SHINY.
    private static final MatterEffect[] EFFECT_ORDER = new MatterEffect[]{
            MatterEffect.BLAZING,
            MatterEffect.GLITCH,
            MatterEffect.SHINY
    };

    private static final String[] COLOR_KEYS;
    private static final String[] SHAPE_NAMES; // index 0 = LIQUID, others = MatterShape ordinals + 1

    // Flattened: key = MATTER_KEYS[(shapeIndex * colorCount + colorOrdinal) * maskCount + mask]
    private static final String[] MATTER_KEYS;

    static {
        // Precompute color keys
        MatterColor[] colors = MatterColor.values();
        COLOR_KEYS = new String[colors.length];
        for (int i = 0; i < colors.length; i++) {
            COLOR_KEYS[i] = "C:" + colors[i].name();
        }

        // Precompute shape names (include LIQUID at index 0)
        MatterShape[] shapes = MatterShape.values();
        SHAPE_NAMES = new String[shapes.length + 1];
        SHAPE_NAMES[0] = SHAPE_LIQUID;
        for (int i = 0; i < shapes.length; i++) {
            SHAPE_NAMES[i + 1] = shapes[i].name();
        }

        int shapeCount = SHAPE_NAMES.length;     // 1 + shapes
        int colorCount = colors.length;
        int maskCount = 1 << MatterEffect.values().length; // 2^N (N=3 -> 8)

        MATTER_KEYS = new String[shapeCount * colorCount * maskCount];

        for (int s = 0; s < shapeCount; s++) {
            String shapeName = SHAPE_NAMES[s];
            for (int c = 0; c < colorCount; c++) {
                String colorName = colors[c].name();
                for (int mask = 0; mask < maskCount; mask++) {
                    String eff = effectString(mask);
                    String key = "M:" + shapeName + ":" + colorName + ":" + eff;

                    int idx = flattenIndex(s, c, mask, colorCount, maskCount);
                    MATTER_KEYS[idx] = key;
                }
            }
        }
    }

    private MatterTelemetryKeys() {}

    public static String colorKey(MatterPayload p) {
        MatterColor c = (p != null && p.color() != null) ? p.color() : MatterColor.RAW;
        return COLOR_KEYS[c.ordinal()];
    }

    public static String matterKey(MatterPayload p) {
        int shapeIndex = 0; // LIQUID by default
        int colorOrdinal = MatterColor.RAW.ordinal();
        int mask = 0;

        if (p != null) {
            MatterShape s = p.shape();
            if (s != null) shapeIndex = s.ordinal() + 1;

            MatterColor c = p.color();
            if (c != null) colorOrdinal = c.ordinal();

            List<MatterEffect> effs = p.effects();
            if (effs != null && !effs.isEmpty()) {
                // Build mask by ordinal (no allocations)
                for (int i = 0; i < effs.size(); i++) {
                    MatterEffect e = effs.get(i);
                    if (e == null) continue;
                    mask |= (1 << e.ordinal());
                }
            }
        }

        int maskCount = 1 << MatterEffect.values().length;
        int idx = flattenIndex(shapeIndex, colorOrdinal, mask, MatterColor.values().length, maskCount);
        return MATTER_KEYS[idx];
    }

    private static int flattenIndex(int shapeIndex, int colorOrdinal, int mask, int colorCount, int maskCount) {
        return (shapeIndex * colorCount + colorOrdinal) * maskCount + mask;
    }

    private static String effectString(int mask) {
        if (mask == 0) return NO_EFFECT;

        StringBuilder sb = new StringBuilder(32);
        boolean first = true;

        for (MatterEffect e : EFFECT_ORDER) {
            int bit = 1 << e.ordinal();
            if ((mask & bit) == 0) continue;

            if (!first) sb.append('+');
            sb.append(e.name());
            first = false;
        }

        // Safety (should never happen)
        if (first) return NO_EFFECT;

        return sb.toString();
    }
}
