package com.matterworks.core.ui.swing.panels;

import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.managers.GridManager;
import com.matterworks.core.managers.TechManager;
import com.matterworks.core.ui.MariaDBAdapter;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.UUID;

public class TechTreePanel extends JPanel {

    @SuppressWarnings("unused")
    private final MariaDBAdapter repository; // kept for compatibility (not used directly)
    private final UUID playerUuid;
    private final GridManager gridManager;

    private final JPanel contentPanel;
    private final JScrollPane scrollPane;
    private final Timer refreshTimer;

    private final JLabel title = new JLabel("RESEARCH & DEVELOPMENT");
    private final JLabel loading = new JLabel("Loading player profile...");

    private final Map<String, CardUI> cardsById = new HashMap<>();
    private List<String> lastNodeOrder = new ArrayList<>();

    // cache signature to skip refresh when nothing relevant changed
    private long lastSignature = Long.MIN_VALUE;

    private static final class CardUI {
        final String nodeId;
        final JPanel card;
        final JLabel lblName;
        final JLabel lblDetail;
        final JButton btnAction;

        CardUI(String nodeId, JPanel card, JLabel lblName, JLabel lblDetail, JButton btnAction) {
            this.nodeId = nodeId;
            this.card = card;
            this.lblName = lblName;
            this.lblDetail = lblDetail;
            this.btnAction = btnAction;
        }
    }

    public TechTreePanel(MariaDBAdapter repository, UUID playerUuid, GridManager gm) {
        this.repository = repository;
        this.playerUuid = playerUuid;
        this.gridManager = gm;

        setLayout(new BorderLayout());
        setBackground(new Color(30, 30, 35));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        contentPanel = new ScrollableContentPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBackground(new Color(30, 30, 35));

        scrollPane = new JScrollPane(contentPanel);
        scrollPane.setBorder(null);
        scrollPane.getViewport().setOpaque(true);
        scrollPane.getViewport().setBackground(new Color(30, 30, 35));

        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);

        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.getHorizontalScrollBar().setUnitIncrement(16);

        add(scrollPane, BorderLayout.CENTER);

        setupStaticHeader();

        // Initial
        ensureCardsBuiltIfNeeded();
        refreshCardsIfNeeded(true);

        refreshTimer = new Timer(1000, e -> {
            if (!isDisplayable()) {
                dispose();
                return;
            }
            if (!isShowing()) return;
            ensureCardsBuiltIfNeeded();
            refreshCardsIfNeeded(false);
        });
        refreshTimer.start();
    }

    public void dispose() {
        if (refreshTimer != null && refreshTimer.isRunning()) refreshTimer.stop();
    }

    private void setupStaticHeader() {
        title.setForeground(Color.ORANGE);
        title.setFont(new Font("Monospaced", Font.BOLD, 16));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);

        loading.setForeground(Color.LIGHT_GRAY);
        loading.setFont(new Font("Monospaced", Font.PLAIN, 12));
        loading.setAlignmentX(Component.LEFT_ALIGNMENT);

        contentPanel.add(title);
        contentPanel.add(Box.createVerticalStrut(12));
        contentPanel.add(loading);
        contentPanel.add(Box.createVerticalStrut(8));
    }

    /**
     * Build cards only if node list changed (order/ids).
     */
    private void ensureCardsBuiltIfNeeded() {
        TechManager tm = gridManager.getTechManager();
        List<TechManager.TechNode> nodes = (List<TechManager.TechNode>) tm.getAllNodes();

        List<String> order = new ArrayList<>(nodes.size());
        for (TechManager.TechNode n : nodes) order.add(n.id());

        if (order.equals(lastNodeOrder) && !cardsById.isEmpty()) return;

        lastNodeOrder = order;

        // rebuild the card region once (not every refresh)
        // Keep scroll positions stable
        int prevV = scrollPane.getVerticalScrollBar().getValue();
        int prevH = scrollPane.getHorizontalScrollBar().getValue();

        // Remove everything after the static header (title + spacing + loading)
        // Header components count is fixed: title, strut, loading, strut
        while (contentPanel.getComponentCount() > 4) {
            contentPanel.remove(contentPanel.getComponentCount() - 1);
        }

        cardsById.clear();

        for (TechManager.TechNode node : nodes) {
            CardUI ui = createTechCardUI(node);
            cardsById.put(node.id(), ui);

            contentPanel.add(ui.card);
            contentPanel.add(Box.createVerticalStrut(10));
        }

        contentPanel.add(Box.createVerticalGlue());
        contentPanel.revalidate();
        contentPanel.repaint();

        SwingUtilities.invokeLater(() -> {
            scrollPane.getVerticalScrollBar().setValue(prevV);
            scrollPane.getHorizontalScrollBar().setValue(prevH);
        });

        // force refresh
        lastSignature = Long.MIN_VALUE;
    }

    /**
     * Refresh only if signature changed, or forced.
     */
    private void refreshCardsIfNeeded(boolean force) {
        PlayerProfile p = gridManager.getCachedProfile(playerUuid);
        TechManager tm = gridManager.getTechManager();

        if (p == null) {
            setVisibleIfChanged(loading, true);
            lastSignature = Long.MIN_VALUE;
            return;
        }

        setVisibleIfChanged(loading, false);

        long sig = computeSignature(p, tm);
        if (!force && sig == lastSignature) return;
        lastSignature = sig;

        // update each card with diff
        for (String nodeId : lastNodeOrder) {
            CardUI ui = cardsById.get(nodeId);
            if (ui == null) continue;

            TechManager.TechNode node = tm.getNode(nodeId);
            if (node == null) continue;

            applyNodeState(ui, node, tm, p);
        }
    }

    private CardUI createTechCardUI(TechManager.TechNode node) {
        boolean isPrestigeNode = node.enablesPrestige() || TechManager.PRESTIGE_NODE_ID.equalsIgnoreCase(node.id());

        JPanel card = new JPanel(new BorderLayout(10, 5));
        card.setBackground(isPrestigeNode ? new Color(55, 35, 70) : new Color(50, 50, 55));
        card.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY, 1));

        card.setPreferredSize(new Dimension(520, 78));
        card.setMaximumSize(new Dimension(520, 78));
        card.setMinimumSize(new Dimension(520, 78));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel info = new JPanel(new GridLayout(2, 1));
        info.setOpaque(false);
        info.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 0));

        JLabel lblName = new JLabel(node.name());
        lblName.setForeground(Color.WHITE);
        lblName.setFont(new Font("SansSerif", Font.BOLD, 13));

        JLabel lblDetail = new JLabel("");
        lblDetail.setForeground(Color.LIGHT_GRAY);
        lblDetail.setFont(new Font("Monospaced", Font.PLAIN, 10));

        info.add(lblName);
        info.add(lblDetail);

        JButton btnAction = new JButton();
        btnAction.setPreferredSize(new Dimension(110, 42));
        btnAction.setFont(new Font("SansSerif", Font.BOLD, 11));
        btnAction.setFocusable(false);

        // listener stays stable; just uses nodeId
        btnAction.addActionListener(e -> {
            boolean ok = gridManager.unlockTechNode(playerUuid, node.id());
            if (ok) {
                // refresh quickly without rebuild
                refreshCardsIfNeeded(true);
            }
        });

        card.add(info, BorderLayout.CENTER);
        card.add(btnAction, BorderLayout.EAST);

        return new CardUI(node.id(), card, lblName, lblDetail, btnAction);
    }

    private void applyNodeState(CardUI ui, TechManager.TechNode node, TechManager tm, PlayerProfile p) {
        boolean unlocked = p.hasTech(node.id());
        boolean parentsUnlocked = tm.areParentsSatisfied(p, node);

        double effectiveCost = tm.getEffectiveNodeCost(p, node.id());
        boolean canAfford = p.isAdmin() || p.getMoney() >= effectiveCost;

        boolean isPrestigeNode = node.enablesPrestige() || TechManager.PRESTIGE_NODE_ID.equalsIgnoreCase(node.id());
        boolean isUpgradeNode = node.isUpgradeNode();

        // Detail text
        String detail = buildNodeDetail(node, isPrestigeNode, isUpgradeNode);

        String costText;
        if (p.isAdmin()) {
            costText = "Cost: $0 (ADMIN) | " + detail;
        } else {
            int eff = (int) Math.round(effectiveCost);
            costText = "Cost: $" + eff + " | " + detail;
        }

        setTextIfChanged(ui.lblName, node.name());
        setTextIfChanged(ui.lblDetail, costText);

        // Button state (diff)
        if (unlocked) {
            setButtonIfChanged(ui.btnAction,
                    "ACQUIRED",
                    new Color(80, 80, 80),
                    Color.LIGHT_GRAY,
                    false
            );
        } else if (!parentsUnlocked) {
            setButtonIfChanged(ui.btnAction,
                    "LOCKED",
                    new Color(120, 40, 40),
                    Color.WHITE,
                    false
            );
        } else {
            setButtonIfChanged(ui.btnAction,
                    "UNLOCK",
                    canAfford ? new Color(40, 140, 40) : new Color(100, 100, 40),
                    Color.WHITE,
                    p.isAdmin() || canAfford
            );
        }
    }

    private String buildNodeDetail(TechManager.TechNode node, boolean isPrestigeNode, boolean isUpgradeNode) {
        if (isPrestigeNode) return "Unlocks: PRESTIGE";

        if (isUpgradeNode) {
            StringJoiner sj = new StringJoiner(", ");
            if (node.upgradeMachineIds() != null) {
                for (String id : node.upgradeMachineIds()) sj.add(id);
            }

            String tierTxt = "Tier " + node.upgradeToTier();
            String speedTxt = "Speed x" + trim2(node.safeSpeedMultiplier());

            String nexusTxt = "";
            if (node.targetsMachine("nexus_core")) {
                nexusTxt = " | Sell x" + trim2(node.safeNexusSellMultiplier());
            }

            return "Upgrade: " + tierTxt + " | " + speedTxt + nexusTxt + " | Targets: " + sj;
        }

        String unlockText = (node.unlockItemIds() == null) ? "" : String.join(", ", node.unlockItemIds());
        if (unlockText.isBlank()) return "Unlocks: -";
        return "Unlocks: " + unlockText;
    }

    private String trim2(double v) {
        double x = Math.round(v * 100.0) / 100.0;
        return String.format(Locale.US, "%.2f", x);
    }

    private long computeSignature(PlayerProfile p, TechManager tm) {
        // We want: unlock set + money (rounded) + admin flag.
        // This avoids refreshing 60 times when nothing changed.
        long h = 1469598103934665603L;

        h ^= (p.isAdmin() ? 1 : 0); h *= 1099511628211L;
        long moneyKey = Math.round(p.getMoney()); // enough for enabling logic
        h ^= (moneyKey ^ (moneyKey >>> 32)); h *= 1099511628211L;

        for (String id : lastNodeOrder) {
            boolean has = p.hasTech(id);
            h ^= (has ? 1 : 0); h *= 1099511628211L;
        }

        // If your effective costs depend on prestige/tier etc, the unlock bits already cover it well enough.
        // If you need more sensitivity later, we can add a prestigeKey here.

        return h;
    }

    // ===== diff helpers =====

    private static void setVisibleIfChanged(JComponent c, boolean v) {
        if (c == null) return;
        if (c.isVisible() == v) return;
        c.setVisible(v);
    }

    private static void setTextIfChanged(JLabel lbl, String txt) {
        if (lbl == null) return;
        if (Objects.equals(lbl.getText(), txt)) return;
        lbl.setText(txt);
    }

    private static void setButtonIfChanged(JButton b, String text, Color bg, Color fg, boolean enabled) {
        if (!Objects.equals(b.getText(), text)) b.setText(text);
        if (!Objects.equals(b.getBackground(), bg)) b.setBackground(bg);
        if (!Objects.equals(b.getForeground(), fg)) b.setForeground(fg);
        if (b.isEnabled() != enabled) b.setEnabled(enabled);
    }

    /**
     * Key trick: do NOT force the content panel to track viewport width,
     * otherwise Swing will never show the horizontal scrollbar.
     */
    private static final class ScrollableContentPanel extends JPanel implements Scrollable {

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 64;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return false;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
