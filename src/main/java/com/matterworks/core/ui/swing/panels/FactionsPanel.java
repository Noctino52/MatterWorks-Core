package com.matterworks.core.ui.swing.panels;

import com.matterworks.core.domain.factions.FactionDefinition;
import com.matterworks.core.domain.factions.FactionPricingRule;
import com.matterworks.core.domain.factions.FactionRotationInfo;
import com.matterworks.core.domain.factions.FactionRotationSlot;
import com.matterworks.core.domain.factions.FactionRuleEnums;
import com.matterworks.core.managers.GridManager;
import com.matterworks.core.ui.MariaDBAdapter;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.*;
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

    public FactionsPanel(MariaDBAdapter repository, GridManager gridManager) {
        this.repository = repository;
        this.gridManager = gridManager;

        setLayout(new BorderLayout());
        setBackground(BG);
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        add(buildTop(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);

        refreshTimer = new Timer(1500, e -> requestRefresh());
        refreshTimer.start();

        requestRefresh();
    }

    public void dispose() {
        disposed = true;
        try { if (refreshTimer != null) refreshTimer.stop(); } catch (Exception ignored) {}
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

        // Schedule box
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
        if (!refreshRunning.compareAndSet(false, true)) return;

        exec.submit(() -> {
            try {
                // CORE-driven: rotation info + schedule (UI does not compute)
                FactionRotationInfo rot = null;
                List<FactionRotationSlot> schedule = List.of();
                try { rot = gridManager.getFactionRotationInfo(); } catch (Throwable ignored) {}
                try { schedule = gridManager.getFactionRotationSchedule(6); } catch (Throwable ignored) {}

                // DB-driven: factions + rules (UI reads DB via adapter)
                List<FactionDefinition> factions = safeLoadFactions();
                Map<Integer, List<FactionPricingRule>> rulesByFaction = new HashMap<>();
                for (FactionDefinition f : factions) {
                    if (f == null) continue;
                    rulesByFaction.put(f.id(), safeLoadRules(f.id()));
                }

                final FactionRotationInfo rotFinal = rot;
                final List<FactionRotationSlot> scheduleFinal = (schedule != null ? schedule : List.of());

                SwingUtilities.invokeLater(() -> {
                    if (disposed) return;
                    applySnapshot(rotFinal, scheduleFinal, factions, rulesByFaction);
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
                               Map<Integer, List<FactionPricingRule>> rulesByFaction) {

        int activeFactionId = 1;
        String activeFactionName = "Faction #1";

        if (rot != null) {
            activeFactionId = Math.max(1, rot.currentFactionId());
            activeFactionName = (rot.currentFactionName() != null) ? rot.currentFactionName() : ("Faction #" + activeFactionId);

            lblCurrent.setText("Current faction: " + activeFactionName + " (#" + activeFactionId + ")");

            if (!rot.enabled()) {
                lblRotation.setText("Rotation: OFF (manual via server_gamestate.active_faction_id)");
                lblNext.setText("Next change: -");
            } else {
                lblRotation.setText("Rotation: ON  | every " + rot.rotationHours() + " hour(s)");

                String remainingTxt = formatDuration(rot.remainingMs());
                String atTxt = (rot.nextChangeEpochMs() > 0)
                        ? TIME_FMT.format(Instant.ofEpochMilli(rot.nextChangeEpochMs()))
                        : "?";

                lblNext.setText("Next change in: " + remainingTxt + "  | at " + atTxt
                        + "  | next: " + rot.nextFactionName());
            }
        } else {
            lblCurrent.setText("Current faction: #" + activeFactionId);
            lblRotation.setText("Rotation: ?");
            lblNext.setText("Next change: ?");
        }

        // Schedule render (no calculations)
        renderSchedule(schedule);

        // Cards
        listWrap.removeAll();

        if (factions.isEmpty()) {
            JLabel empty = new JLabel("No factions found in DB.");
            empty.setForeground(Color.LIGHT_GRAY);
            empty.setFont(new Font("SansSerif", Font.PLAIN, 12));
            listWrap.add(empty);
            listWrap.revalidate();
            listWrap.repaint();
            return;
        }

        for (FactionDefinition f : factions) {
            if (f == null) continue;

            boolean isActive = (f.id() == activeFactionId);

            List<FactionPricingRule> rules = rulesByFaction.getOrDefault(f.id(), List.of());
            listWrap.add(buildFactionCard(f, rules, isActive));
            listWrap.add(Box.createVerticalStrut(10));
        }

        listWrap.add(Box.createVerticalGlue());
        listWrap.revalidate();
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

        String title = f.displayName();
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
                .thenComparing(Comparator.comparingInt(FactionPricingRule::priority).reversed())
                .thenComparingLong(FactionPricingRule::id);

        likes.sort(cmp);
        dislikes.sort(cmp);

        card.add(buildRuleSection("APPREZZA", LIKE_GREEN, likes));
        card.add(Box.createVerticalStrut(6));
        card.add(buildRuleSection("DISPREZZA", DISLIKE_RED, dislikes));

        card.setMaximumSize(new Dimension(380, 9999));
        return card;
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

        JLabel badge = new JLabel(renderBadgeText(r));
        badge.setForeground(SOFT_GRAY);
        badge.setFont(new Font("SansSerif", Font.PLAIN, 10));

        wrap.add(badge);
        return wrap;
    }

    private String renderRuleText(FactionPricingRule r) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;

        if (r.color() != null) {
            sb.append("color=").append(r.color().name());
            first = false;
        }
        if (r.shape() != null) {
            if (!first) sb.append(" + ");
            sb.append("shape=").append(r.shape().name());
            first = false;
        }
        if (r.effect() != null) {
            if (!first) sb.append(" + ");
            sb.append("effect=").append(r.effect().name());
        }

        if (sb.length() == 0) sb.append("(invalid rule)");
        return sb.toString();
    }

    private String renderBadgeText(FactionPricingRule r) {
        String mt = (r.matchType() != null) ? r.matchType().name() : "CONTAINS";
        String cm = (r.combineMode() != null) ? r.combineMode().name() : "ALL";

        if ("EXACT".equals(mt)) return "ABSOLUTE (EXACT match)";
        return "CONTAINS " + cm;
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String formatDuration(long ms) {
        if (ms < 0) return "-";
        long totalSec = ms / 1000L;
        long h = totalSec / 3600L;
        long m = (totalSec % 3600L) / 60L;
        long s = totalSec % 60L;
        return String.format("%02d:%02d:%02d", h, m, s);
    }
}
