package com.matterworks.core.ui.swing.panels;

import com.matterworks.core.domain.factions.FactionDefinition;
import com.matterworks.core.domain.factions.FactionPricingRule;
import com.matterworks.core.domain.factions.FactionRotationInfo;
import com.matterworks.core.domain.factions.FactionRotationSlot;
import com.matterworks.core.domain.factions.FactionRuleEnums;
import com.matterworks.core.managers.GridManager;
import com.matterworks.core.ui.MariaDBAdapter;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.TitledBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class FactionsPanel extends JPanel {

    private static final Color BG = new Color(30, 30, 35);
    private static final Color CARD = new Color(50, 50, 55);
    private static final Color ACCENT = new Color(90, 180, 255);

    private static final Color LIKE_GREEN = new Color(70, 210, 90);
    private static final Color DISLIKE_RED = new Color(220, 90, 90);
    private static final Color SOFT_GRAY = new Color(180, 180, 180);

    private final MariaDBAdapter repository;
    private final GridManager gridManager;

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mw-factions-panel");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean refreshRunning = new AtomicBoolean(false);
    private final Timer refreshTimer;

    private volatile boolean disposed = false;

    // UI - header
    private final JLabel lblCurrent = new JLabel("Current faction: ...");
    private final JLabel lblRotation = new JLabel("Rotation: ...");
    private final JLabel lblNext = new JLabel("Next change: ...");

    // UI - schedule (next windows)
    private final JPanel scheduleBox = new JPanel();

    // Cards list
    private final JPanel listWrap = new JPanel();

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    // ---- caching to avoid UI rebuild storms ----
    private long lastDbSignature = Long.MIN_VALUE;
    private long lastScheduleSignature = Long.MIN_VALUE;
    private int lastActiveFactionId = Integer.MIN_VALUE;

    private final Map<Integer, JPanel> factionCardsById = new HashMap<>();
    private final Map<Integer, FactionDefinition> factionDefById = new HashMap<>();

    public FactionsPanel(MariaDBAdapter repository, GridManager gridManager) {
        this.repository = repository;
        this.gridManager = gridManager;

        setLayout(new BorderLayout());
        setBackground(BG);
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        add(buildTop(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);

        // IMPORTANT: javax.swing.Timer
        refreshTimer = new Timer(1500, e -> requestRefresh());
        refreshTimer.start();

        requestRefresh();
    }

    public void dispose() {
        disposed = true;
        try { refreshTimer.stop(); } catch (Exception ignored) {}
        try { exec.shutdownNow(); } catch (Exception ignored) {}
    }

    private JComponent buildTop() {
        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("FACTIONS");
        title.setForeground(ACCENT);
        title.setFont(new Font("Monospaced", Font.BOLD, 16));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);

        lblCurrent.setForeground(Color.WHITE);
        lblCurrent.setFont(new Font("Monospaced", Font.BOLD, 12));
        lblCurrent.setAlignmentX(Component.CENTER_ALIGNMENT);

        lblRotation.setForeground(SOFT_GRAY);
        lblRotation.setFont(new Font("SansSerif", Font.PLAIN, 11));
        lblRotation.setAlignmentX(Component.CENTER_ALIGNMENT);

        lblNext.setForeground(SOFT_GRAY);
        lblNext.setFont(new Font("SansSerif", Font.PLAIN, 11));
        lblNext.setAlignmentX(Component.CENTER_ALIGNMENT);

        scheduleBox.setOpaque(false);
        scheduleBox.setLayout(new BoxLayout(scheduleBox, BoxLayout.Y_AXIS));
        scheduleBox.setBorder(BorderFactory.createEmptyBorder(8, 6, 6, 6));

        top.add(title);
        top.add(Box.createVerticalStrut(6));
        top.add(lblCurrent);
        top.add(Box.createVerticalStrut(4));
        top.add(lblRotation);
        top.add(Box.createVerticalStrut(2));
        top.add(lblNext);
        top.add(Box.createVerticalStrut(6));
        top.add(scheduleBox);
        top.add(Box.createVerticalStrut(8));

        return top;
    }

    private JComponent buildCenter() {
        listWrap.setOpaque(false);
        listWrap.setLayout(new BoxLayout(listWrap, BoxLayout.Y_AXIS));

        JScrollPane scroll = new JScrollPane(listWrap);
        scroll.setBorder(null);
        scroll.getViewport().setOpaque(false);
        scroll.setOpaque(false);

        return scroll;
    }

    private void requestRefresh() {
        if (disposed) return;
        if (!isDisplayable()) return;
        if (!isShowing()) return; // if tab hidden, don't hammer EDT
        if (!refreshRunning.compareAndSet(false, true)) return;

        exec.submit(() -> {
            try {
                FactionRotationInfo rot = null;
                List<FactionRotationSlot> schedule = List.of();

                try { rot = gridManager.getFactionRotationInfo(); } catch (Throwable ignored) {}
                try { schedule = gridManager.getFactionRotationSchedule(6); } catch (Throwable ignored) {}

                List<FactionDefinition> factions = safeLoadFactions();
                Map<Integer, List<FactionPricingRule>> rulesByFaction = new HashMap<>();
                for (FactionDefinition f : factions) {
                    if (f == null) continue;
                    rulesByFaction.put(f.id(), safeLoadRules(f.id()));
                }

                long dbSig = computeDbSignature(factions, rulesByFaction);
                long schedSig = computeScheduleSignature(schedule);

                final FactionRotationInfo rotFinal = rot;
                final List<FactionRotationSlot> scheduleFinal = (schedule != null ? schedule : List.of());
                final List<FactionDefinition> factionsFinal = factions;
                final Map<Integer, List<FactionPricingRule>> rulesFinal = rulesByFaction;

                SwingUtilities.invokeLater(() -> {
                    if (disposed || !isDisplayable() || !isShowing()) return;
                    applySnapshot(rotFinal, scheduleFinal, factionsFinal, rulesFinal, dbSig, schedSig);
                });

            } finally {
                refreshRunning.set(false);
            }
        });
    }

    private List<FactionDefinition> safeLoadFactions() {
        try {
            List<FactionDefinition> list = repository.loadFactions();
            return list != null ? list : List.of();
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    private List<FactionPricingRule> safeLoadRules(int factionId) {
        try {
            List<FactionPricingRule> list = repository.loadFactionRules(factionId);
            return list != null ? list : List.of();
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    private void applySnapshot(FactionRotationInfo rot,
                               List<FactionRotationSlot> schedule,
                               List<FactionDefinition> factions,
                               Map<Integer, List<FactionPricingRule>> rulesByFaction,
                               long dbSignature,
                               long scheduleSignature) {

        int activeFactionId = 1;
        String activeFactionName = "Faction #1";

        if (rot != null) {
            activeFactionId = Math.max(1, rot.currentFactionId());
            activeFactionName = (rot.currentFactionName() != null) ? rot.currentFactionName() : ("Faction #" + activeFactionId);

            setLabelIfChanged(lblCurrent, "Current faction: " + activeFactionName + " (#" + activeFactionId + ")");

            if (!rot.enabled()) {
                setLabelIfChanged(lblRotation, "Rotation: OFF (manual via server_gamestate.active_faction_id)");
                setLabelIfChanged(lblNext, "Next change: -");
            } else {
                setLabelIfChanged(lblRotation, "Rotation: ON  | every " + rot.rotationHours() + " hour(s)");

                String remainingTxt = formatDuration(rot.remainingMs());
                String atTxt = (rot.nextChangeEpochMs() > 0)
                        ? TIME_FMT.format(Instant.ofEpochMilli(rot.nextChangeEpochMs()))
                        : "?";

                setLabelIfChanged(lblNext, "Next change in: " + remainingTxt + "  | at " + atTxt
                        + "  | next: " + rot.nextFactionName());
            }
        } else {
            setLabelIfChanged(lblCurrent, "Current faction: #" + activeFactionId);
            setLabelIfChanged(lblRotation, "Rotation: ?");
            setLabelIfChanged(lblNext, "Next change: ?");
        }

        // Schedule: rebuild only if changed
        if (scheduleSignature != lastScheduleSignature) {
            lastScheduleSignature = scheduleSignature;
            renderSchedule(schedule);
        }

        // Cards: rebuild only if DB signature changed
        if (dbSignature != lastDbSignature) {
            lastDbSignature = dbSignature;
            rebuildFactionCards(activeFactionId, factions, rulesByFaction);
        } else {
            // If only active faction changed, update borders only
            if (activeFactionId != lastActiveFactionId) {
                updateActiveBorders(activeFactionId);
            }
        }

        lastActiveFactionId = activeFactionId;
    }

    private void rebuildFactionCards(int activeFactionId,
                                     List<FactionDefinition> factions,
                                     Map<Integer, List<FactionPricingRule>> rulesByFaction) {

        factionCardsById.clear();
        factionDefById.clear();

        listWrap.removeAll();

        if (factions == null || factions.isEmpty()) {
            JLabel empty = new JLabel("No factions found in DB.");
            empty.setForeground(Color.LIGHT_GRAY);
            empty.setFont(new Font("SansSerif", Font.PLAIN, 12));
            listWrap.add(empty);
            listWrap.revalidate();
            listWrap.repaint();
            lastActiveFactionId = activeFactionId;
            return;
        }

        for (FactionDefinition f : factions) {
            if (f == null) continue;

            factionDefById.put(f.id(), f);

            boolean isActive = (f.id() == activeFactionId);
            List<FactionPricingRule> rules = rulesByFaction.getOrDefault(f.id(), List.of());

            JPanel card = (JPanel) buildFactionCard(f, rules, isActive);
            factionCardsById.put(f.id(), card);

            listWrap.add(card);
            listWrap.add(Box.createVerticalStrut(10));
        }

        listWrap.add(Box.createVerticalGlue());
        listWrap.revalidate();
        listWrap.repaint();

        lastActiveFactionId = activeFactionId;
    }

    private void updateActiveBorders(int activeFactionId) {
        for (Map.Entry<Integer, JPanel> e : factionCardsById.entrySet()) {
            int id = e.getKey();
            JPanel card = e.getValue();
            FactionDefinition def = factionDefById.get(id);
            if (card == null || def == null) continue;

            boolean isActive = (id == activeFactionId);
            applyCardBorder(card, def, isActive);
        }
        listWrap.repaint();
    }

    private void renderSchedule(List<FactionRotationSlot> schedule) {
        scheduleBox.removeAll();

        JLabel head = new JLabel("NEXT WINDOWS");
        head.setForeground(ACCENT);
        head.setFont(new Font("SansSerif", Font.BOLD, 11));
        scheduleBox.add(head);
        scheduleBox.add(Box.createVerticalStrut(4));

        if (schedule == null || schedule.isEmpty()) {
            JLabel none = new JLabel("(rotation off or no schedule)");
            none.setForeground(SOFT_GRAY);
            none.setFont(new Font("SansSerif", Font.ITALIC, 11));
            scheduleBox.add(none);
            scheduleBox.revalidate();
            scheduleBox.repaint();
            return;
        }

        for (FactionRotationSlot s : schedule) {
            scheduleBox.add(buildScheduleRow(s));
            scheduleBox.add(Box.createVerticalStrut(2));
        }

        scheduleBox.revalidate();
        scheduleBox.repaint();
    }

    private JComponent buildScheduleRow(FactionRotationSlot s) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);

        String range = TIME_FMT.format(Instant.ofEpochMilli(s.startEpochMs()))
                + " - " + TIME_FMT.format(Instant.ofEpochMilli(s.endEpochMs()));

        JLabel left = new JLabel(range);
        left.setForeground(SOFT_GRAY);
        left.setFont(new Font("Monospaced", Font.PLAIN, 11));

        String name = s.factionName() != null ? s.factionName() : ("Faction #" + s.factionId());
        JLabel right = new JLabel((s.isCurrent() ? "NOW: " : "") + name);
        right.setForeground(s.isCurrent() ? Color.WHITE : SOFT_GRAY);
        right.setFont(new Font("SansSerif", s.isCurrent() ? Font.BOLD : Font.PLAIN, 11));
        right.setHorizontalAlignment(SwingConstants.RIGHT);

        row.add(left, BorderLayout.WEST);
        row.add(right, BorderLayout.CENTER);
        return row;
    }

    private JComponent buildFactionCard(FactionDefinition f, List<FactionPricingRule> rules, boolean isActive) {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(CARD);

        applyCardBorder(card, f, isActive);

        if (f.description() != null && !f.description().isBlank()) {
            JLabel desc = new JLabel("<html><body style='width: 290px;'>" + escapeHtml(f.description()) + "</body></html>");
            desc.setForeground(SOFT_GRAY);
            desc.setFont(new Font("SansSerif", Font.PLAIN, 11));
            desc.setBorder(BorderFactory.createEmptyBorder(4, 8, 6, 8));
            card.add(desc);
        }

        List<FactionPricingRule> likes = new ArrayList<>();
        List<FactionPricingRule> dislikes = new ArrayList<>();

        if (rules != null) {
            for (FactionPricingRule r : rules) {
                if (r == null) continue;
                if (r.sentiment() == FactionRuleEnums.Sentiment.DISLIKE) dislikes.add(r);
                else likes.add(r);
            }
        }

        Comparator<FactionPricingRule> cmp = Comparator
                .comparingInt(FactionPricingRule::specificityScore).reversed()
                .thenComparingInt(FactionPricingRule::priority).reversed()
                .thenComparingLong(FactionPricingRule::id);

        likes.sort(cmp);
        dislikes.sort(cmp);

        card.add(buildRuleSection("APPREZZA", LIKE_GREEN, likes));
        card.add(Box.createVerticalStrut(6));
        card.add(buildRuleSection("DISPREZZA", DISLIKE_RED, dislikes));

        card.setMaximumSize(new Dimension(380, 9999));
        return card;
    }

    private void applyCardBorder(JPanel card, FactionDefinition f, boolean isActive) {
        String title = f.displayName();
        if (title == null || title.isBlank()) title = "Faction #" + f.id();
        if (isActive) title = title + "  (ACTIVE)";

        TitledBorder tb = BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(Color.DARK_GRAY, 1),
                title,
                TitledBorder.LEFT,
                TitledBorder.TOP,
                new Font("SansSerif", Font.BOLD, 11),
                isActive ? ACCENT : Color.WHITE
        );
        card.setBorder(tb);
    }

    private JComponent buildRuleSection(String label, Color labelColor, List<FactionPricingRule> rules) {
        JPanel sec = new JPanel();
        sec.setOpaque(false);
        sec.setLayout(new BoxLayout(sec, BoxLayout.Y_AXIS));
        sec.setBorder(BorderFactory.createEmptyBorder(2, 8, 8, 8));

        JLabel title = new JLabel(label);
        title.setForeground(labelColor);
        title.setFont(new Font("SansSerif", Font.BOLD, 11));
        sec.add(title);
        sec.add(Box.createVerticalStrut(4));

        if (rules == null || rules.isEmpty()) {
            JLabel none = new JLabel("(none)");
            none.setForeground(SOFT_GRAY);
            none.setFont(new Font("SansSerif", Font.ITALIC, 11));
            sec.add(none);
            return sec;
        }

        for (FactionPricingRule r : rules) {
            sec.add(buildRuleRow(r));
            sec.add(Box.createVerticalStrut(4));
        }

        return sec;
    }

    private JComponent buildRuleRow(FactionPricingRule r) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);

        JLabel left = new JLabel(renderRuleText(r));
        left.setForeground(Color.WHITE);
        left.setFont(new Font("Monospaced", Font.PLAIN, 11));

        JLabel mult = new JLabel("x" + String.format(Locale.US, "%.3f", r.multiplier()));
        mult.setForeground(ACCENT);
        mult.setFont(new Font("Monospaced", Font.BOLD, 11));
        mult.setHorizontalAlignment(SwingConstants.RIGHT);
        mult.setPreferredSize(new Dimension(70, 18));

        row.add(left, BorderLayout.CENTER);
        row.add(mult, BorderLayout.EAST);

        JPanel wrap = new JPanel();
        wrap.setOpaque(false);
        wrap.setLayout(new BoxLayout(wrap, BoxLayout.Y_AXIS));
        wrap.add(row);

        String note = (r.note() != null && !r.note().isBlank()) ? (" • " + r.note()) : "";
        JLabel badge = new JLabel("spec=" + r.specificityScore() + " prio=" + r.priority() + note);
        badge.setForeground(SOFT_GRAY);
        badge.setFont(new Font("SansSerif", Font.PLAIN, 10));
        wrap.add(badge);

        return wrap;
    }

    private String renderRuleText(FactionPricingRule r) {
        // Build a stable, low-cost label without calling non-existing methods.
        StringBuilder sb = new StringBuilder();

        sb.append(r.matchType()).append(" ").append(r.combineMode()).append(": ");

        boolean any = false;
        if (r.shape() != null) {
            sb.append("shape=").append(r.shape().name());
            any = true;
        }
        if (r.color() != null) {
            if (any) sb.append(", ");
            sb.append("color=").append(r.color().name());
            any = true;
        }
        if (r.effect() != null) {
            if (any) sb.append(", ");
            sb.append("effect=").append(r.effect().name());
            any = true;
        }
        if (!any) sb.append("(no filters)");

        return sb.toString();
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static void setLabelIfChanged(JLabel lbl, String txt) {
        if (Objects.equals(lbl.getText(), txt)) return;
        lbl.setText(txt);
    }

    private static String formatDuration(long ms) {
        if (ms <= 0) return "0s";
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = (s % 60);
        if (h > 0) return h + "h " + m + "m " + sec + "s";
        if (m > 0) return m + "m " + sec + "s";
        return sec + "s";
    }

    private static long computeScheduleSignature(List<FactionRotationSlot> schedule) {
        if (schedule == null || schedule.isEmpty()) return 0L;
        long h = 1469598103934665603L;
        for (FactionRotationSlot s : schedule) {
            h ^= s.factionId(); h *= 1099511628211L;
            h ^= (s.isCurrent() ? 1 : 0); h *= 1099511628211L;
            h ^= (s.startEpochMs() ^ (s.startEpochMs() >>> 32)); h *= 1099511628211L;
            h ^= (s.endEpochMs() ^ (s.endEpochMs() >>> 32)); h *= 1099511628211L;
            String name = s.factionName();
            if (name != null) {
                h ^= name.hashCode();
                h *= 1099511628211L;
            }
        }
        return h;
    }

    private static long computeDbSignature(List<FactionDefinition> factions,
                                           Map<Integer, List<FactionPricingRule>> rulesByFaction) {
        if (factions == null || factions.isEmpty()) return 0L;

        long h = 2166136261L;
        for (FactionDefinition f : factions) {
            if (f == null) continue;

            h = mix(h, f.id());
            h = mix(h, safeHash(f.code()));
            h = mix(h, safeHash(f.displayName()));
            h = mix(h, safeHash(f.description()));
            h = mix(h, f.sortOrder());

            List<FactionPricingRule> rules = (rulesByFaction != null)
                    ? rulesByFaction.getOrDefault(f.id(), List.of())
                    : List.of();

            h = mix(h, rules.size());
            for (FactionPricingRule r : rules) {
                if (r == null) continue;

                h = mix(h, (int) (r.id() ^ (r.id() >>> 32)));
                h = mix(h, r.factionId());
                h = mix(h, safeHash(String.valueOf(r.sentiment())));
                h = mix(h, safeHash(String.valueOf(r.matchType())));
                h = mix(h, safeHash(String.valueOf(r.combineMode())));
                h = mix(h, safeHash(r.color() != null ? r.color().name() : null));
                h = mix(h, safeHash(r.shape() != null ? r.shape().name() : null));
                h = mix(h, safeHash(r.effect() != null ? r.effect().name() : null));
                h = mix(h, (int) Math.round(r.multiplier() * 1_000_000));
                h = mix(h, r.priority());
                h = mix(h, safeHash(r.note()));
            }
        }
        return h;
    }

    private static long mix(long h, int v) {
        h ^= v;
        h *= 16777619L;
        return h;
    }

    private static int safeHash(String s) {
        return (s != null) ? s.hashCode() : 0;
    }
}
