package com.matterworks.core.infrastructure.swing;

import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.managers.GridManager;
import com.matterworks.core.managers.TechManager;
import com.matterworks.core.ports.IRepository;

import javax.swing.*;
import java.awt.*;
import java.util.UUID;

public class TechTreePanel extends JPanel {

    private final IRepository repository;
    private final UUID playerUuid;
    private final GridManager gridManager;

    public TechTreePanel(IRepository repository, UUID playerUuid, GridManager gm) {
        this.repository = repository;
        this.playerUuid = playerUuid;
        this.gridManager = gm;

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(new Color(30, 30, 35));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Titolo
        JLabel title = new JLabel("R&D Department");
        title.setForeground(Color.ORANGE);
        title.setFont(new Font("Monospaced", Font.BOLD, 16));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        add(title);
        add(Box.createVerticalStrut(15));

        // Rendering Nodi
        TechManager tm = gridManager.getTechManager();
        for (TechManager.TechNode node : tm.getAllNodes()) {
            add(createTechCard(node, tm));
            add(Box.createVerticalStrut(10));
        }

        // Refresh automatico per aggiornare stati (soldi/unlocks)
        new Timer(1000, e -> {
            removeAll();
            add(title);
            add(Box.createVerticalStrut(15));
            for (TechManager.TechNode node : tm.getAllNodes()) {
                add(createTechCard(node, tm));
                add(Box.createVerticalStrut(10));
            }
            revalidate();
            repaint();
        }).start();
    }

    private JPanel createTechCard(TechManager.TechNode node, TechManager tm) {
        PlayerProfile p = repository.loadPlayerProfile(playerUuid);
        boolean unlocked = p.hasTech(node.id());
        boolean parentUnlocked = (node.parentId() == null) || p.hasTech(node.parentId());
        boolean canAfford = p.getMoney() >= node.cost();

        JPanel card = new JPanel(new BorderLayout(10, 5));
        card.setBackground(new Color(50, 50, 55));
        card.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY, 1));
        card.setMaximumSize(new Dimension(300, 60));
        card.setPreferredSize(new Dimension(300, 60));

        // Info Sinistra
        JPanel info = new JPanel(new GridLayout(2, 1));
        info.setOpaque(false);
        JLabel lblName = new JLabel(node.name());
        lblName.setForeground(Color.WHITE);
        lblName.setFont(new Font("SansSerif", Font.BOLD, 13));

        JLabel lblCost = new JLabel("$ " + (int)node.cost() + " | Item: " + node.unlocksItemId());
        lblCost.setForeground(Color.LIGHT_GRAY);
        lblCost.setFont(new Font("Monospaced", Font.PLAIN, 11));

        info.add(lblName);
        info.add(lblCost);
        info.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 0));

        // Bottone Azione Destra
        JButton btnAction = new JButton();
        btnAction.setPreferredSize(new Dimension(90, 40));
        btnAction.setFont(new Font("SansSerif", Font.BOLD, 11));
        btnAction.setFocusable(false);

        if (unlocked) {
            btnAction.setText("ACQUISITO");
            btnAction.setBackground(Color.GRAY);
            btnAction.setForeground(Color.DARK_GRAY);
            btnAction.setEnabled(false);
        } else if (!parentUnlocked) {
            btnAction.setText("BLOCCATO");
            btnAction.setBackground(new Color(100, 30, 30)); // Rosso scuro
            btnAction.setForeground(Color.WHITE);
            btnAction.setEnabled(false); // Disabilitato perché manca dipendenza
            btnAction.setToolTipText("Richiede: " + tm.getNode(node.parentId()).name());
        } else {
            btnAction.setText("SBLOCCA");
            btnAction.setBackground(canAfford ? new Color(30, 120, 30) : new Color(80, 80, 30)); // Verde o Giallo scuro
            btnAction.setForeground(Color.WHITE);
            btnAction.addActionListener(e -> {
                if (tm.unlockNode(p, node.id())) {
                    // Force refresh immediato
                    revalidate();
                    repaint();
                }
            });
        }

        card.add(info, BorderLayout.CENTER);
        card.add(btnAction, BorderLayout.EAST);

        return card;
    }
}