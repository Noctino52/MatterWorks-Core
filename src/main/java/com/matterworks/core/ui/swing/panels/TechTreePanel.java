package com.matterworks.core.ui.swing.panels;

import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.managers.GridManager;
import com.matterworks.core.managers.TechManager;
import com.matterworks.core.ui.MariaDBAdapter;

import javax.swing.*;
import javax.swing.Timer;
import java.awt.*;
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

        renderNodes();

        refreshTimer = new Timer(1000, e -> {
            if (!this.isDisplayable()) {
                dispose();
                return;
            }
            renderNodes();
        });
        refreshTimer.start();
    }

    public void dispose() {
        if (refreshTimer != null && refreshTimer.isRunning()) refreshTimer.stop();
    }

    private void renderNodes() {
        int prevV = scrollPane.getVerticalScrollBar().getValue();
        int prevH = scrollPane.getHorizontalScrollBar().getValue();

        contentPanel.removeAll();

        JLabel title = new JLabel("RESEARCH & DEVELOPMENT");
        title.setForeground(Color.ORANGE);
        title.setFont(new Font("Monospaced", Font.BOLD, 16));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        contentPanel.add(title);
        contentPanel.add(Box.createVerticalStrut(12));

        TechManager tm = gridManager.getTechManager();
        PlayerProfile p = gridManager.getCachedProfile(playerUuid);

        if (p == null) {
            JLabel loading = new JLabel("Loading player profile...");
            loading.setForeground(Color.LIGHT_GRAY);
            loading.setFont(new Font("Monospaced", Font.PLAIN, 12));
            loading.setAlignmentX(Component.LEFT_ALIGNMENT);
            contentPanel.add(loading);

            contentPanel.revalidate();
            contentPanel.repaint();

            SwingUtilities.invokeLater(() -> {
                scrollPane.getVerticalScrollBar().setValue(prevV);
                scrollPane.getHorizontalScrollBar().setValue(prevH);
            });
            return;
        }

        for (TechManager.TechNode node : tm.getAllNodes()) {
            contentPanel.add(createTechCard(node, tm, p));
            contentPanel.add(Box.createVerticalStrut(10));
        }

        contentPanel.revalidate();
        contentPanel.repaint();

        SwingUtilities.invokeLater(() -> {
            scrollPane.getVerticalScrollBar().setValue(prevV);
            scrollPane.getHorizontalScrollBar().setValue(prevH);
        });
    }

    private JPanel createTechCard(TechManager.TechNode node, TechManager tm, PlayerProfile p) {
        boolean unlocked = p.hasTech(node.id());
        boolean parentsUnlocked = tm.areParentsSatisfied(p, node);

        double effectiveCost = tm.getEffectiveNodeCost(p, node.id());
        boolean canAfford = p.isAdmin() || p.getMoney() >= effectiveCost;

        boolean isPrestigeNode = node.enablesPrestige() || TechManager.PRESTIGE_NODE_ID.equalsIgnoreCase(node.id());
        boolean isUpgradeNode = node.isUpgradeNode();

        JPanel card = new JPanel(new BorderLayout(10, 5));
        card.setBackground(isPrestigeNode ? new Color(55, 35, 70) : new Color(50, 50, 55));
        card.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY, 1));

        // Important: give cards a fixed-ish width so horizontal scrolling makes sense.
        // If the panel is smaller than this, horizontal scrollbar will appear.
        card.setPreferredSize(new Dimension(520, 78));
        card.setMaximumSize(new Dimension(520, 78));
        card.setMinimumSize(new Dimension(520, 78));

        card.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel info = new JPanel(new GridLayout(2, 1));
        info.setOpaque(false);

        JLabel lblName = new JLabel(node.name());
        lblName.setForeground(Color.WHITE);
        lblName.setFont(new Font("SansSerif", Font.BOLD, 13));

        String detail = buildNodeDetail(node, isPrestigeNode, isUpgradeNode);

        String costText;
        if (p.isAdmin()) {
            costText = "Cost: $0 (ADMIN) | " + detail;
        } else {
            int eff = (int) Math.round(effectiveCost);
            costText = "Cost: $" + eff + " | " + detail;
        }

        JLabel lblDetail = new JLabel(costText);
        lblDetail.setForeground(Color.LIGHT_GRAY);
        lblDetail.setFont(new Font("Monospaced", Font.PLAIN, 10));

        info.add(lblName);
        info.add(lblDetail);
        info.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 0));

        JButton btnAction = new JButton();
        btnAction.setPreferredSize(new Dimension(110, 42));
        btnAction.setFont(new Font("SansSerif", Font.BOLD, 11));
        btnAction.setFocusable(false);

        if (unlocked) {
            btnAction.setText("ACQUIRED");
            btnAction.setBackground(new Color(80, 80, 80));
            btnAction.setForeground(Color.LIGHT_GRAY);
            btnAction.setEnabled(false);
        } else if (!parentsUnlocked) {
            btnAction.setText("LOCKED");
            btnAction.setBackground(new Color(120, 40, 40));
            btnAction.setForeground(Color.WHITE);
            btnAction.setEnabled(false);
        } else {
            btnAction.setText("UNLOCK");
            btnAction.setBackground(canAfford ? new Color(40, 140, 40) : new Color(100, 100, 40));
            btnAction.setForeground(Color.WHITE);
            btnAction.setEnabled(p.isAdmin() || canAfford);

            btnAction.addActionListener(e -> {
                boolean ok = gridManager.unlockTechNode(playerUuid, node.id());
                if (ok) renderNodes();
            });
        }

        card.add(info, BorderLayout.CENTER);
        card.add(btnAction, BorderLayout.EAST);

        return card;
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
        return String.format(java.util.Locale.US, "%.2f", x);
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
            return false; // <-- enables horizontal scroll
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
