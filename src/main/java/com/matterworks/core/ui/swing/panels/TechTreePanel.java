package com.matterworks.core.ui.swing.panels;

import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.managers.GridManager;
import com.matterworks.core.managers.TechManager;
import com.matterworks.core.ui.MariaDBAdapter;

import javax.swing.*;
import java.awt.*;
import java.util.UUID;

public class TechTreePanel extends JPanel {

    private final MariaDBAdapter repository; // compat
    private final UUID playerUuid;
    private final GridManager gridManager;
    private final Timer refreshTimer;

    public TechTreePanel(MariaDBAdapter repository, UUID playerUuid, GridManager gm) {
        this.repository = repository;
        this.playerUuid = playerUuid;
        this.gridManager = gm;

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(new Color(30, 30, 35));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

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
        removeAll();

        JLabel title = new JLabel("RESEARCH & DEVELOPMENT");
        title.setForeground(Color.ORANGE);
        title.setFont(new Font("Monospaced", Font.BOLD, 16));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        add(title);
        add(Box.createVerticalStrut(15));

        TechManager tm = gridManager.getTechManager();
        PlayerProfile p = gridManager.getCachedProfile(playerUuid);
        if (p == null) return;

        for (TechManager.TechNode node : tm.getAllNodes()) {
            add(createTechCard(node, tm, p));
            add(Box.createVerticalStrut(10));
        }

        revalidate();
        repaint();
    }

    private JPanel createTechCard(TechManager.TechNode node, TechManager tm, PlayerProfile p) {
        boolean unlocked = p.hasTech(node.id());

        boolean parentsUnlocked = tm.areParentsSatisfied(p, node);

        double effectiveCost = tm.getEffectiveNodeCost(p, node.id());
        boolean canAfford = p.isAdmin() || p.getMoney() >= effectiveCost;

        boolean isPrestigeNode = tm.isPrestigeUnlockTech(node.id());

        JPanel card = new JPanel(new BorderLayout(10, 5));
        card.setBackground(isPrestigeNode ? new Color(55, 35, 70) : new Color(50, 50, 55));
        card.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY, 1));
        card.setMaximumSize(new Dimension(310, 70));
        card.setPreferredSize(new Dimension(310, 70));

        JPanel info = new JPanel(new GridLayout(2, 1));
        info.setOpaque(false);

        JLabel lblName = new JLabel(node.name());
        lblName.setForeground(Color.WHITE);
        lblName.setFont(new Font("SansSerif", Font.BOLD, 13));

        String unlockText = (node.unlockItemIds() == null) ? "" : String.join(", ", node.unlockItemIds());
        if (unlockText.isBlank() && isPrestigeNode) unlockText = "Unlocks Prestige Button";

        String costText;
        if (p.isAdmin()) {
            costText = "Cost: $0 (ADMIN) | Unlocks: " + unlockText;
        } else {
            int eff = (int) Math.round(effectiveCost);
            costText = "Cost: $" + eff + " | Unlocks: " + unlockText;
        }

        JLabel lblDetail = new JLabel(costText);
        lblDetail.setForeground(Color.LIGHT_GRAY);
        lblDetail.setFont(new Font("Monospaced", Font.PLAIN, 10));

        info.add(lblName);
        info.add(lblDetail);
        info.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 0));

        JButton btnAction = new JButton();
        btnAction.setPreferredSize(new Dimension(100, 40));
        btnAction.setFont(new Font("SansSerif", Font.BOLD, 11));
        btnAction.setFocusable(false);

        if (unlocked) {
            btnAction.setText("ACQUISITO");
            btnAction.setBackground(new Color(80, 80, 80));
            btnAction.setForeground(Color.LIGHT_GRAY);
            btnAction.setEnabled(false);
        } else if (!parentsUnlocked) {
            btnAction.setText("BLOCCATO");
            btnAction.setBackground(new Color(120, 40, 40));
            btnAction.setForeground(Color.WHITE);
            btnAction.setEnabled(false);
        } else {
            btnAction.setText("SBLOCCA");
            btnAction.setBackground(canAfford ? new Color(40, 140, 40) : new Color(100, 100, 40));
            btnAction.setForeground(Color.WHITE);
            btnAction.addActionListener(e -> {
                if (tm.unlockNode(p, node.id())) renderNodes();
            });
        }

        card.add(info, BorderLayout.CENTER);
        card.add(btnAction, BorderLayout.EAST);

        return card;
    }
}
