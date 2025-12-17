package com.matterworks.core.infrastructure.swing;

import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.managers.GridManager;
import com.matterworks.core.ports.IRepository;

import javax.swing.*;
import java.awt.*;
import java.util.UUID;

public class InventoryDebugPanel extends JPanel {

    private final IRepository repository;
    private final UUID playerUuid;
    private final GridManager gridManager;
    private final String[] itemIds = {"drill_mk1", "conveyor_belt", "nexus_core", "chromator", "color_mixer"};

    public InventoryDebugPanel(IRepository repository, UUID playerUuid, GridManager gm) {
        this.repository = repository;
        this.playerUuid = playerUuid;
        this.gridManager = gm;

        // FIX: Imposta dimensione preferita per evitare che BorderLayout lo schiacci
        this.setPreferredSize(new Dimension(280, 0));

        PlayerProfile profile = repository.loadPlayerProfile(playerUuid);
        boolean isPlayer = (profile != null && profile.getRank() == PlayerProfile.PlayerRank.PLAYER);

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createTitledBorder(isPlayer ? "Warehouse Shop" : "Warehouse Monitor"));
        setBackground(new Color(40, 40, 40));

        for (String id : itemIds) {
            add(createItemRow(id, isPlayer));
            add(Box.createVerticalStrut(5));
        }

        new Timer(1000, e -> repaint()).start();
    }

    private JPanel createItemRow(String itemId, boolean isPlayer) {
        JPanel row = new JPanel(new BorderLayout(5, 0));
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(260, 35)); // Aumentata leggermente altezza

        JLabel lblInfo = new JLabel() {
            @Override
            protected void paintComponent(Graphics g) {
                setText(itemId + ": " + repository.getInventoryItemCount(playerUuid, itemId));
                super.paintComponent(g);
            }
        };
        lblInfo.setForeground(Color.WHITE);
        lblInfo.setFont(new Font("Monospaced", Font.PLAIN, 12));

        JPanel buttons = new JPanel(new GridLayout(1, 2, 5, 0));
        buttons.setOpaque(false);

        JButton btnAdd = createTinyButton("+", () -> {
            if (isPlayer) gridManager.buyItem(playerUuid, itemId, 1);
            else repository.modifyInventoryItem(playerUuid, itemId, 1);
        });
        btnAdd.setBackground(new Color(50, 100, 50));

        JButton btnRem = createTinyButton("-", () -> {
            if (isPlayer) {
                if (repository.getInventoryItemCount(playerUuid, itemId) > 0) {
                    double refund = gridManager.getBlockRegistry().getPrice(itemId) * 0.5;
                    PlayerProfile p = repository.loadPlayerProfile(playerUuid);
                    p.modifyMoney(refund);
                    repository.savePlayerProfile(p);
                    repository.modifyInventoryItem(playerUuid, itemId, -1);
                }
            } else {
                repository.modifyInventoryItem(playerUuid, itemId, -1);
            }
        });
        btnRem.setBackground(new Color(100, 50, 50));

        buttons.add(btnRem);
        buttons.add(btnAdd);
        row.add(lblInfo, BorderLayout.CENTER);
        row.add(buttons, BorderLayout.EAST);
        return row;
    }

    private JButton createTinyButton(String t, Runnable a) {
        JButton b = new JButton(t);
        b.setPreferredSize(new Dimension(30, 25));
        b.setFont(new Font("SansSerif", Font.BOLD, 12));
        b.setFocusable(false);
        b.setForeground(Color.WHITE);
        b.addActionListener(e -> { a.run(); repaint(); });
        return b;
    }
}