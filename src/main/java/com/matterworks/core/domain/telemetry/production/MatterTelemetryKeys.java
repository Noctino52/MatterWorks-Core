package com.matterworks.core.domain.telemetry.production;

import com.matterworks.core.domain.matter.MatterColor;
import com.matterworks.core.domain.matter.MatterEffect;
import com.matterworks.core.domain.matter.MatterPayload;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Stable string keys for telemetry.
 *
 * COLOR key example:
 *   C:RED
 *
 * FULL MATTER key example:
 *   M:CUBE:RED:BLAZING+SHINY
 *   M:LIQUID:RAW:NO_EFFECT
 */
public final class MatterTelemetryKeys {

    private MatterTelemetryKeys() {}

    public static String colorKey(MatterPayload p) {
        MatterColor c = (p != null && p.color() != null) ? p.color() : MatterColor.RAW;
        return "C:" + c.name();
    }

    public static String matterKey(MatterPayload p) {
        String shape = (p != null && p.shape() != null) ? p.shape().name() : "LIQUID";
        String color = (p != null && p.color() != null) ? p.color().name() : MatterColor.RAW.name();

        String eff = "NO_EFFECT";
        if (p != null && p.effects() != null && !p.effects().isEmpty()) {
            List<MatterEffect> tmp = new ArrayList<>(p.effects());
            tmp.sort(Comparator.comparing(e -> e.name().toUpperCase(Locale.ROOT)));
            eff = tmp.stream().map(Enum::name).collect(Collectors.joining("+"));
        }

        return "M:" + shape + ":" + color + ":" + eff;
    }
}
