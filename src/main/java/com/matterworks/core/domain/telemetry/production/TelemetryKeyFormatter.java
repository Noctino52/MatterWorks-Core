package com.matterworks.core.domain.telemetry.production;

import java.util.Locale;

/**
 * Parses telemetry keys and provides user-friendly labels.
 *
 * Supported formats:
 * - Color:  C:RED
 * - Matter: M:CUBE:RED:BLAZING+SHINY
 *          M:LIQUID:RAW:NO_EFFECT
 *
 * Special rule:
 * If a matter key represents a "color-only" payload (no shape/effects),
 * it is encoded as M:LIQUID:<COLOR>:NO_EFFECT and rendered as "Color: <Color>".
 */
public final class TelemetryKeyFormatter {

    private TelemetryKeyFormatter() {}

    public static String toLabel(String key) {
        if (key == null || key.isBlank()) return "UNKNOWN";

        if (key.startsWith("C:")) {
            String color = safePart(key, 1);
            return "Color: " + prettify(color);
        }

        if (key.startsWith("M:")) {
            String shape = safePart(key, 1);
            String color = safePart(key, 2);
            String effects = safePart(key, 3);

            boolean noEffects = (effects == null || effects.isBlank() || "NO_EFFECT".equalsIgnoreCase(effects));
            boolean isColorOnly = "LIQUID".equalsIgnoreCase(shape) && noEffects;

            if (isColorOnly) {
                return "Color: " + prettify(color);
            }

            String s = prettify(shape);
            String c = prettify(color);

            String eff = noEffects ? "No effects" : prettifyEffects(effects);

            return s + " / " + c + " / " + eff;
        }

        return key;
    }

    public static KeyKind kindOf(String key) {
        if (key == null) return KeyKind.MATTER;
        if (key.startsWith("C:")) return KeyKind.COLOR;
        return KeyKind.MATTER;
    }

    private static String safePart(String key, int indexAfterPrefix) {
        // "C:RED" -> parts: ["C", "RED"]
        // "M:CUBE:RED:E1+E2" -> parts: ["M", "CUBE", "RED", "E1+E2"]
        String[] parts = key.split(":");
        int idx = indexAfterPrefix;
        if (idx < 0 || idx >= parts.length) return "UNKNOWN";
        return parts[idx];
    }

    private static String prettify(String raw) {
        if (raw == null) return "Unknown";
        String x = raw.trim().replace('_', ' ').toLowerCase(Locale.ROOT);
        if (x.isEmpty()) return "Unknown";
        return Character.toUpperCase(x.charAt(0)) + x.substring(1);
    }

    private static String prettifyEffects(String raw) {
        // "BLAZING+SHINY" -> "Blazing + Shiny"
        String[] eff = raw.split("\\+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < eff.length; i++) {
            if (i > 0) sb.append(" + ");
            sb.append(prettify(eff[i]));
        }
        return sb.toString();
    }
}
