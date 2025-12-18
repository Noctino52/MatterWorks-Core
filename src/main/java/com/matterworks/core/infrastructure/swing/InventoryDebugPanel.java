package com.matterworks.core.infrastructure.swing;

import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.managers.GridManager;
import com.matterworks.core.ports.IRepository;
import javax.swing.*;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class InventoryDebugPanel extends JPanel {

    private final IRepository repository;
    private final UUID playerUuid;
    private final GridManager gridManager;
    private final String[] itemIds = {"drill_mk1", "conveyor_belt", "nexus_core", "chromator", "color_mixer"};
    private final Map<String, JLabel> labelMap = new HashMap<>();

    // Timer salvato in campo per poterlo fermare
    private final Timer refreshTimer;

    public InventoryDebugPanel(IRepository repository, UUID playerUuid, GridManager gm) {
        this.repository = repository;
        this.playerUuid = playerUuid;
        this.gridManager = gm;

        this.setPreferredSize(new Dimension(320, 0));
        this.setMinimumSize(new Dimension(320, 0));

        PlayerProfile profile = gridManager.getCachedProfile(playerUuid); // Use cache
        boolean isPlayer = (profile != null && profile.getRank() == PlayerProfile.PlayerRank.PLAYER);

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createTitledBorder(isPlayer ? "Warehouse Shop" : "Warehouse Monitor"));
        setBackground(new Color(40, 40, 40));

        for (String id : itemIds) {
            add(createItemRow(id, isPlayer));
            add(Box.createVerticalStrut(8));
        }

        // Timer a 500ms
        refreshTimer = new Timer(500, e -> updateAllLabels());
        refreshTimer.start();
    }

    /**
     * IMPORTANTE: Ferma il timer per evitare memory leak e CPU usage in background
     */
    public void dispose() {
        if (refreshTimer != null && refreshTimer.isRunning()) {
            refreshTimer.stop();
        }
    }

    private JPanel createItemRow(String itemId, boolean isPlayer) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(310, 40));

        JLabel lblInfo = new JLabel(itemId + ": 0");
        lblInfo.setForeground(Color.WHITE);
        lblInfo.setFont(new Font("Monospaced", Font.BOLD, 12));
        labelMap.put(itemId, lblInfo);

        JPanel buttons = new JPanel(new GridLayout(1, 2, 5, 0));
        buttons.setOpaque(false);
        buttons.setPreferredSize(new Dimension(80, 28));
        buttons.setMinimumSize(new Dimension(80, 28));

        JButton btnAdd = createTinyButton("+", () -> {
            if (isPlayer) gridManager.buyItem(playerUuid, itemId, 1);
            else repository.modifyInventoryItem(playerUuid, itemId, 1);
        });
        btnAdd.setBackground(new Color(50, 110, 50));

        JButton btnRem = createTinyButton("-", () -> {
            if (isPlayer) {
                if (repository.getInventoryItemCount(playerUuid, itemId) > 0) {
                    double refund = gridManager.getBlockRegistry().getPrice(itemId) * 0.5;
                    PlayerProfile p = gridManager.getCachedProfile(playerUuid);
                    if (p != null) {
                        p.modifyMoney(refund);
                        repository.savePlayerProfile(p);
                        repository.modifyInventoryItem(playerUuid, itemId, -1);
                    }
                }
            } else {
                repository.modifyInventoryItem(playerUuid, itemId, -1);
            }
        });
        btnRem.setBackground(new Color(120, 50, 50));

        buttons.add(btnRem);
        buttons.add(btnAdd);

        row.add(lblInfo, BorderLayout.CENTER);
        row.add(buttons, BorderLayout.EAST);
        return row;
    }

    private void updateAllLabels() {
        // Safe check: se il pannello non è più visualizzabile, fermati
        if (!this.isDisplayable()) {
            dispose();
            return;
        }
        for (String id : itemIds) {
            JLabel lbl = labelMap.get(id);
            if (lbl != null) {
                int count = repository.getInventoryItemCount(playerUuid, id);
                lbl.setText(id + ": " + count);
            }
        }
    }

    private JButton createTinyButton(String t, Runnable a) {
        JButton b = new JButton(t);
        b.setPreferredSize(new Dimension(35, 25));
        b.setMargin(new Insets(0, 0, 0, 0));
        b.setFont(new Font("SansSerif", Font.BOLD, 14));
        b.setFocusable(false);
        b.setForeground(Color.WHITE);
        b.addActionListener(e -> { a.run(); updateAllLabels(); });
        return b;
    }
}