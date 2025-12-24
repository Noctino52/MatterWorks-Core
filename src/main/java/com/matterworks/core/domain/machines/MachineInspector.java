package com.matterworks.core.domain.machines;

import com.google.gson.JsonObject;
import com.matterworks.core.common.GridPosition;
import com.matterworks.core.domain.inventory.MachineInventory;
import com.matterworks.core.domain.matter.MatterColor;
import com.matterworks.core.domain.matter.MatterEffect;
import com.matterworks.core.domain.matter.MatterPayload;
import com.matterworks.core.domain.matter.MatterShape;

import java.util.*;

/**
 * Costruisce MachineInspectionInfo leggendo SOLO lo stato runtime/metadata
 * senza alterare alcuna logica esistente.
 */
public final class MachineInspector {

    private static final Set<String> HIDE_UI_TYPES = Set.of(
            "conveyor_belt",
            "splitter",
            "merger",
            "lift",
            "dropper",
            "nexus_core"
    );

    private MachineInspector() {}

    public static MachineInspectionInfo inspect(PlacedMachine m) {
        if (m == null) return null;

        String typeId = safe(m.getTypeId(), "unknown");
        String name = typeId; // fallback: displayName futuro
        GridPosition anchor = m.getPos();

        boolean showInUi = shouldShowInUi(typeId);

        List<String> input = new ArrayList<>();
        List<String> output = new ArrayList<>();
        List<String> target = new ArrayList<>();

        int matterCount = 0;
        int colorCount = 0;

        MachineWorkState state = MachineWorkState.FERMA;

        try {
            // ==========================================================
            // PROCESSOR MACHINES (inputBuffer/outputBuffer/currentRecipe)
            // ==========================================================
            if (m instanceof ProcessorMachine pm) {
                // Conteggi + righe input/output reali
                addInventoryLines(pm.inputBuffer, input);
                addInventoryLines(pm.outputBuffer, output);

                matterCount += pm.inputBuffer.getMatterCount();
                matterCount += pm.outputBuffer.getMatterCount();
                colorCount += pm.inputBuffer.getColorCount();
                colorCount += pm.outputBuffer.getColorCount();

                if (pm.currentRecipe != null) {
                    state = MachineWorkState.LAVORANDO;
                    MatterPayload out = pm.currentRecipe.output();
                    if (out != null) target.add(formatPayload(out, 1));
                } else {
                    state = MachineWorkState.FERMA;

                    // planned output: “che output sta cercando di generare con quelli dentro”
                    MatterPayload planned = computePlannedOutput(pm);
                    if (planned != null) target.add(formatPayload(planned, 1));
                }

                return new MachineInspectionInfo(
                        name, typeId, anchor, state,
                        matterCount, colorCount,
                        List.copyOf(input),
                        List.copyOf(output),
                        List.copyOf(target),
                        showInUi
                );
            }

            // ==========================================================
            // DRILL (metadata items + mining_resource)
            // ==========================================================
            if (typeId.startsWith("drill")) {
                JsonObject meta = safeSerialize(m);
                MachineInventory inv = inventoryFromMetadata(meta);

                addInventoryLines(inv, output);
                matterCount += inv.getMatterCount();
                colorCount += inv.getColorCount();

                MatterColor res = readMiningResource(meta);
                MatterPayload produced = new MatterPayload((res == MatterColor.RAW) ? MatterShape.CUBE : null, res);

                // SCAVANDO se potrebbe ancora inserire il prossimo item
                state = inv.canInsert(produced) ? MachineWorkState.SCAVANDO : MachineWorkState.FERMA;

                target.add(formatPayload(produced, 1));

                return new MachineInspectionInfo(
                        name, typeId, anchor, state,
                        matterCount, colorCount,
                        List.copyOf(input),
                        List.copyOf(output),
                        List.copyOf(target),
                        showInUi
                );
            }

            // ==========================================================
            // BELT (metadata currentItem)
            // ==========================================================
            if ("conveyor_belt".equals(typeId)) {
                JsonObject meta = safeSerialize(m);
                MatterPayload current = null;
                if (meta != null && meta.has("currentItem") && meta.get("currentItem").isJsonObject()) {
                    current = MatterPayload.fromJson(meta.getAsJsonObject("currentItem"));
                }

                if (current != null) {
                    input.add(formatPayload(current, 1));
                    if (current.shape() != null) matterCount += 1;
                    else colorCount += 1;
                    state = MachineWorkState.LAVORANDO;
                } else {
                    state = MachineWorkState.FERMA;
                }

                return new MachineInspectionInfo(
                        name, typeId, anchor, state,
                        matterCount, colorCount,
                        List.copyOf(input),
                        List.copyOf(output),
                        List.copyOf(target),
                        showInUi
                );
            }

            // ==========================================================
            // GENERIC INVENTORY MACHINES (metadata items)
            // splitter/merger/lift/dropper/nexus ecc.
            // ==========================================================
            {
                JsonObject meta = safeSerialize(m);
                MachineInventory inv = inventoryFromMetadata(meta);

                // Non sappiamo “input vs output” in modo univoco: mettiamo tutto in input come “contenuto interno”
                addInventoryLines(inv, input);
                matterCount += inv.getMatterCount();
                colorCount += inv.getColorCount();

                state = inv.isEmpty() ? MachineWorkState.FERMA : MachineWorkState.LAVORANDO;

                // target: per ora non definito (logistica), ma lo raccogliamo comunque
                return new MachineInspectionInfo(
                        name, typeId, anchor, state,
                        matterCount, colorCount,
                        List.copyOf(input),
                        List.copyOf(output),
                        List.copyOf(target),
                        showInUi
                );
            }

        } catch (Throwable t) {
            // safe fallback: non blocca UI/tick
            return new MachineInspectionInfo(
                    name, typeId, anchor, MachineWorkState.FERMA,
                    0, 0,
                    List.of(), List.of(), List.of(),
                    showInUi
            );
        }
    }

    // ==========================================================
    // Planned output per ProcessorMachine (replica la logica dei tick senza consumare)
    // ==========================================================
    private static MatterPayload computePlannedOutput(ProcessorMachine pm) {
        if (pm == null) return null;

        // se output "pieno" secondo la regola già usata nei tick, non pianifica
        if (pm.outputBuffer.getCount() >= ProcessorMachine.MAX_OUTPUT_STACK) return null;

        // Shaper: CUBE -> SPHERE (mantiene color + effects)
        if (pm instanceof ShaperMachine) {
            if (pm.inputBuffer.getCountInSlot(0) <= 0) return null;
            MatterPayload in = pm.inputBuffer.getItemInSlot(0);
            if (in == null || in.shape() != MatterShape.CUBE) return null;
            return new MatterPayload(MatterShape.SPHERE, in.color(), in.effects());
        }

        // Cutting: SPHERE -> PYRAMID (mantiene color + effects)
        if (pm instanceof CuttingMachine) {
            if (pm.inputBuffer.getCountInSlot(0) <= 0) return null;
            MatterPayload in = pm.inputBuffer.getItemInSlot(0);
            if (in == null || in.shape() != MatterShape.SPHERE) return null;
            return new MatterPayload(MatterShape.PYRAMID, in.color(), in.effects());
        }

        // Chromator: slot0 “base”, slot1 “dye” => output: shape base + color dye (effects NON preservati, come nel tick)
        if (pm instanceof Chromator) {
            if (pm.inputBuffer.getCountInSlot(0) <= 0) return null;
            if (pm.inputBuffer.getCountInSlot(1) <= 0) return null;
            MatterPayload base = pm.inputBuffer.getItemInSlot(0);
            MatterPayload dye = pm.inputBuffer.getItemInSlot(1);
            if (base == null || dye == null) return null;
            return new MatterPayload(base.shape(), dye.color());
        }

        // ColorMixer: combina due colori != RAW e != uguali => output: SPHERE mixed (come nel tick)
        if (pm instanceof ColorMixer) {
            if (pm.inputBuffer.getCountInSlot(0) <= 0) return null;
            if (pm.inputBuffer.getCountInSlot(1) <= 0) return null;
            MatterPayload c1 = pm.inputBuffer.getItemInSlot(0);
            MatterPayload c2 = pm.inputBuffer.getItemInSlot(1);
            if (c1 == null || c2 == null) return null;
            if (c1.color() == MatterColor.RAW || c2.color() == MatterColor.RAW) return null;
            if (c1.color() == c2.color()) return null;

            MatterColor mixed = MatterColor.mix(c1.color(), c2.color());
            if (mixed == MatterColor.RAW) mixed = MatterColor.WHITE; // safety: come tick
            return new MatterPayload(MatterShape.SPHERE, mixed);
        }

        // EffectApplicator: output = stesso shape/color + effetto specifico (derivato dal typeId)
        if (pm instanceof EffectApplicatorMachine) {
            if (pm.inputBuffer.getCountInSlot(0) <= 0) return null;
            MatterPayload in = pm.inputBuffer.getItemInSlot(0);
            if (in == null) return null;
            if (in.shape() == null) return null;
            if (in.effects() != null && !in.effects().isEmpty()) return null;

            MatterEffect eff = effectFromType(pm.getTypeId());
            if (eff == null) return null;

            return new MatterPayload(in.shape(), in.color(), List.of(eff));
        }

        // Unknown processor -> niente planned
        return null;
    }

    private static MatterEffect effectFromType(String typeId) {
        if (typeId == null) return null;
        return switch (typeId) {
            case "shiny_polisher" -> MatterEffect.SHINY;
            case "blazing_forge" -> MatterEffect.BLAZING;
            case "glitch_distorter" -> MatterEffect.GLITCH;
            default -> null;
        };
    }

    // ==========================================================
    // Formatting helpers (richiesto dallo standard user)
    // ==========================================================
    private static String formatPayload(MatterPayload p, int count) {
        if (p == null) return "-";

        int c = Math.max(1, count);

        if (p.shape() != null) {
            // Matter (shape, color, effects) xN
            StringBuilder sb = new StringBuilder();
            sb.append("Matter (");
            sb.append(p.shape().name());
            sb.append(", ");
            sb.append(p.color() != null ? p.color().name() : "RAW");

            List<MatterEffect> eff = p.effects();
            if (eff != null && !eff.isEmpty()) {
                sb.append(", effects=");
                for (int i = 0; i < eff.size(); i++) {
                    if (i > 0) sb.append("+");
                    sb.append(eff.get(i).name());
                }
            }
            sb.append(") x").append(c);
            return sb.toString();
        }

        // Colore/Liquido: COLOR xN
        String col = (p.color() != null) ? p.color().name() : "RAW";
        return col + " x" + c;
    }

    private static void addInventoryLines(MachineInventory inv, List<String> out) {
        if (inv == null || out == null) return;
        for (var e : inv.snapshot()) {
            out.add(formatPayload(e.item(), e.count()));
        }
    }

    private static MachineInventory inventoryFromMetadata(JsonObject meta) {
        if (meta == null || !meta.has("items")) return new MachineInventory(0);

        // Preferisci capacity se c'è, altrimenti size items
        try {
            return MachineInventory.fromSerialized(meta);
        } catch (Throwable ignored) {
            // fallback ultra-safe
            return new MachineInventory(0);
        }
    }

    private static MatterColor readMiningResource(JsonObject meta) {
        if (meta == null || !meta.has("mining_resource")) return MatterColor.RAW;
        try {
            return MatterColor.valueOf(meta.get("mining_resource").getAsString());
        } catch (Exception ignored) {
            return MatterColor.RAW;
        }
    }

    private static JsonObject safeSerialize(PlacedMachine m) {
        try {
            return m.serialize();
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean shouldShowInUi(String typeId) {
        if (typeId == null) return true;
        if (HIDE_UI_TYPES.contains(typeId)) return false;
        // extra safety: qualsiasi cosa che contenga “nexus”
        return !typeId.toLowerCase(Locale.ROOT).contains("nexus");
    }

    private static String safe(String s, String def) {
        return (s == null || s.isBlank()) ? def : s;
    }
}
