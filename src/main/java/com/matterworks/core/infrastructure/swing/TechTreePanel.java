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

        renderNodes();

        // Timer di aggiornamento per riflettere i cambiamenti di saldo o sblocchi
        new Timer(1000, e -> renderNodes()).start();
    }

    private void renderNodes() {
        removeAll();

        // Titolo Pannello
        JLabel title = new JLabel("RESEARCH & DEVELOPMENT");
        title.setForeground(Color.ORANGE);
        title.setFont(new Font("Monospaced", Font.BOLD, 16));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        add(title);
        add(Box.createVerticalStrut(15));

        TechManager tm = gridManager.getTechManager();
        PlayerProfile p = repository.loadPlayerProfile(playerUuid);

        if (p == null) return;

        // Itera sui nodi caricati dal DB
        for (TechManager.TechNode node : tm.getAllNodes()) {
            add(createTechCard(node, tm, p));
            add(Box.createVerticalStrut(10));
        }

        revalidate();
        repaint();
    }

    private JPanel createTechCard(TechManager.TechNode node, TechManager tm, PlayerProfile p) {
        boolean unlocked = p.hasTech(node.id());

        // Verifica che TUTTI i genitori siano sbloccati (Logica AND)
        boolean parentsUnlocked = true;
        for (String parentId : node.parentIds()) {
            if (!p.hasTech(parentId)) {
                parentsUnlocked = false;
                break;
            }
        }

        boolean canAfford = p.getMoney() >= node.cost();

        JPanel card = new JPanel(new BorderLayout(10, 5));
        card.setBackground(new Color(50, 50, 55));
        card.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY, 1));
        card.setMaximumSize(new Dimension(310, 70));
        card.setPreferredSize(new Dimension(310, 70));

        // Info: Nome e Dettagli
        JPanel info = new JPanel(new GridLayout(2, 1));
        info.setOpaque(false);
        JLabel lblName = new JLabel(node.name());
        lblName.setForeground(Color.WHITE);
        lblName.setFont(new Font("SansSerif", Font.BOLD, 13));

        String unlockText = String.join(", ", node.unlockItemIds());
        JLabel lblDetail = new JLabel("Cost: $" + (int)node.cost() + " | Unlocks: " + unlockText);
        lblDetail.setForeground(Color.LIGHT_GRAY);
        lblDetail.setFont(new Font("Monospaced", Font.PLAIN, 10));

        info.add(lblName);
        info.add(lblDetail);
        info.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 0));

        // Bottone Azione
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
            btnAction.setBackground(new Color(120, 40, 40)); // Rosso
            btnAction.setForeground(Color.WHITE);
            btnAction.setEnabled(false);

            // Tooltip per mostrare i requisiti
            if (!node.parentIds().isEmpty()) {
                btnAction.setToolTipText("Richiede: " + String.join(", ", node.parentIds()));
            }
        } else {
            btnAction.setText("SBLOCCA");
            btnAction.setBackground(canAfford ? new Color(40, 140, 40) : new Color(100, 100, 40)); // Verde o Giallo
            btnAction.setForeground(Color.WHITE);
            btnAction.addActionListener(e -> {
                if (tm.unlockNode(p, node.id())) {
                    renderNodes(); // Refresh immediato
                }
            });
        }

        card.add(info, BorderLayout.CENTER);
        card.add(btnAction, BorderLayout.EAST);

        return card;
    }
}