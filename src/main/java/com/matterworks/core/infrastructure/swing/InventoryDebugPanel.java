package com.matterworks.core.infrastructure.swing;

import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.managers.GridManager;
import com.matterworks.core.ports.IRepository;
import javax.swing.*;
import java.awt.*;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;

public class InventoryDebugPanel extends JPanel {

    private final IRepository repository;
    private final UUID playerUuid;
    private final GridManager gridManager;
    private final String[] itemIds = {"drill_mk1", "conveyor_belt", "nexus_core", "chromator", "color_mixer"};
    // Mappa per aggiornare le label in modo pulito
    private final Map<String, JLabel> labelMap = new HashMap<>();

    public InventoryDebugPanel(IRepository repository, UUID playerUuid, GridManager gm) {
        this.repository = repository;
        this.playerUuid = playerUuid;
        this.gridManager = gm;

        // 1. Aumentiamo la larghezza del pannello intero a 320px
        this.setPreferredSize(new Dimension(320, 0));
        this.setMinimumSize(new Dimension(320, 0));

        PlayerProfile profile = repository.loadPlayerProfile(playerUuid);
        boolean isPlayer = (profile != null && profile.getRank() == PlayerProfile.PlayerRank.PLAYER);

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createTitledBorder(isPlayer ? "Warehouse Shop" : "Warehouse Monitor"));
        setBackground(new Color(40, 40, 40));

        for (String id : itemIds) {
            add(createItemRow(id, isPlayer));
            add(Box.createVerticalStrut(8)); // Più spazio tra le righe
        }

        // Timer di aggiornamento: setta il testo esplicitamente invece di farlo nel paintComponent
        new Timer(500, e -> updateAllLabels()).start();
    }

    private JPanel createItemRow(String itemId, boolean isPlayer) {
        // Layout con gap orizzontale di 10
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        // Fondamentale: Rimuoviamo il MaximumSize o lo rendiamo molto largo per evitare troncamenti
        row.setMaximumSize(new Dimension(310, 40));

        JLabel lblInfo = new JLabel(itemId + ": 0");
        lblInfo.setForeground(Color.WHITE);
        lblInfo.setFont(new Font("Monospaced", Font.BOLD, 12));
        labelMap.put(itemId, lblInfo);

        // Pannello bottoni con dimensione minima garantita
        JPanel buttons = new JPanel(new GridLayout(1, 2, 5, 0));
        buttons.setOpaque(false);
        // Aumentiamo leggermente a 80px per dare spazio ai bottoni da 35px
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
                    PlayerProfile p = repository.loadPlayerProfile(playerUuid);
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
        // 35px di larghezza è più sicuro per evitare i puntini "..."
        b.setPreferredSize(new Dimension(35, 25));
        b.setMargin(new Insets(0, 0, 0, 0)); // Rimuove i margini interni che causano il troncamento
        b.setFont(new Font("SansSerif", Font.BOLD, 14));
        b.setFocusable(false);
        b.setForeground(Color.WHITE);
        b.addActionListener(e -> { a.run(); updateAllLabels(); });
        return b;
    }
}