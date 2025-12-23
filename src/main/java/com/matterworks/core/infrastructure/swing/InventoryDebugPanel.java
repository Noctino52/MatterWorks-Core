package com.matterworks.core.infrastructure.swing;

import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.managers.GridManager;
import com.matterworks.core.ports.IRepository;

import javax.swing.*;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public class InventoryDebugPanel extends JPanel {

    private final IRepository repository;
    private final UUID playerUuid;
    private final GridManager gridManager;
    private final Runnable onEconomyMaybeChanged;

    private final String[] itemIds = {
            "drill_mk1",
            "conveyor_belt",
            "splitter",
            "merger",
            "lift",
            "dropper",
            "nexus_core",
            "chromator",
            "color_mixer"
    };

    private final Map<String, JLabel> labelMap = new HashMap<>();
    private final Timer refreshTimer;

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mw-inventory-panel-worker");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean disposed = false;

    public InventoryDebugPanel(IRepository repository, UUID playerUuid, GridManager gm, Runnable onEconomyMaybeChanged) {
        this.repository = repository;
        this.playerUuid = playerUuid;
        this.gridManager = gm;
        this.onEconomyMaybeChanged = onEconomyMaybeChanged;

        this.setPreferredSize(new Dimension(320, 0));
        this.setMinimumSize(new Dimension(320, 0));

        PlayerProfile profile = gridManager.getCachedProfile(playerUuid);
        boolean isPlayer = (profile != null && profile.getRank() == PlayerProfile.PlayerRank.PLAYER);

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createTitledBorder(isPlayer ? "Warehouse Shop" : "Warehouse Monitor"));
        setBackground(new Color(40, 40, 40));

        for (String id : itemIds) {
            add(createItemRow(id, isPlayer));
            add(Box.createVerticalStrut(8));
        }

        // Refresh periodico come prima, ma fetch OFF-EDT.
        refreshTimer = new Timer(500, e -> requestRefresh());
        refreshTimer.start();

        // Non chiudere mai qui: il pannello potrebbe non essere ancora displayable.
        // Il primo refresh “buono” arriverà appena la tab viene resa visibile.
        requestRefresh();
    }

    public void dispose() {
        disposed = true;
        try {
            if (refreshTimer != null && refreshTimer.isRunning()) refreshTimer.stop();
        } catch (Exception ignored) {}

        try { exec.shutdownNow(); } catch (Exception ignored) {}
    }

    private JPanel createItemRow(String itemId, boolean isPlayer) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(310, 40));

        double price = gridManager.getBlockRegistry().getPrice(itemId);

        JLabel lblInfo = new JLabel(itemId + ": 0");
        lblInfo.setForeground(Color.WHITE);
        lblInfo.setFont(new Font("Monospaced", Font.BOLD, 12));
        if (isPlayer) lblInfo.setToolTipText("Price: $" + price);

        labelMap.put(itemId, lblInfo);

        JPanel buttons = new JPanel(new GridLayout(1, 2, 5, 0));
        buttons.setOpaque(false);
        buttons.setPreferredSize(new Dimension(80, 28));
        buttons.setMinimumSize(new Dimension(80, 28));

        JButton btnRem = new JButton("-");
        JButton btnAdd = new JButton("+");

        setupTinyButton(btnRem, new Color(120, 50, 50));
        setupTinyButton(btnAdd, new Color(50, 110, 50));

        if (isPlayer) btnAdd.setToolTipText("Buy for $" + price);

        btnAdd.addActionListener(e -> runAsyncButton(btnAdd, () -> {
            if (isPlayer) {
                boolean ok = gridManager.buyItem(playerUuid, itemId, 1);
                if (!ok) System.out.println("Purchase failed: " + itemId);
            } else {
                repository.modifyInventoryItem(playerUuid, itemId, 1);
            }
        }, true));

        btnRem.addActionListener(e -> runAsyncButton(btnRem, () -> {
            if (isPlayer) {
                int have = repository.getInventoryItemCount(playerUuid, itemId);
                if (have > 0) {
                    double refund = gridManager.getBlockRegistry().getPrice(itemId) * 0.5;
                    gridManager.addMoney(playerUuid, refund, "ITEM_SELL", itemId);
                    repository.modifyInventoryItem(playerUuid, itemId, -1);
                }
            } else {
                repository.modifyInventoryItem(playerUuid, itemId, -1);
            }
        }, true));

        buttons.add(btnRem);
        buttons.add(btnAdd);

        row.add(lblInfo, BorderLayout.CENTER);
        row.add(buttons, BorderLayout.EAST);
        return row;
    }

    private void setupTinyButton(JButton b, Color bg) {
        b.setPreferredSize(new Dimension(35, 25));
        b.setMargin(new Insets(0, 0, 0, 0));
        b.setFont(new Font("SansSerif", Font.BOLD, 14));
        b.setFocusable(false);
        b.setForeground(Color.WHITE);
        b.setBackground(bg);
    }

    private void runAsyncButton(JButton btn, Runnable action, boolean economyChanged) {
        if (disposed) return;

        btn.setEnabled(false);

        try {
            exec.submit(() -> {
                try {
                    action.run();
                } catch (Throwable t) {
                    t.printStackTrace();
                } finally {
                    // Aggiorna contatori + soldi
                    requestRefresh();
                    if (economyChanged && onEconomyMaybeChanged != null) {
                        SwingUtilities.invokeLater(onEconomyMaybeChanged);
                    }

                    SwingUtilities.invokeLater(() -> {
                        if (btn.isDisplayable()) btn.setEnabled(true);
                    });
                }
            });
        } catch (RejectedExecutionException ex) {
            // Executor chiuso (tab cambiata). Riabilita il bottone.
            SwingUtilities.invokeLater(() -> {
                if (btn.isDisplayable()) btn.setEnabled(true);
            });
        }
    }

    private void requestRefresh() {
        if (disposed) return;

        // Se non è ancora visibile/displayable, non “killare” nulla: semplicemente rimanda.
        // Il timer riproverà.
        if (!isShowing()) return;

        try {
            exec.submit(() -> {
                Map<String, Integer> counts = new HashMap<>();
                try {
                    for (String id : itemIds) {
                        counts.put(id, repository.getInventoryItemCount(playerUuid, id));
                    }
                } catch (Throwable t) {
                    t.printStackTrace();
                }

                SwingUtilities.invokeLater(() -> {
                    if (disposed || !isDisplayable()) return;
                    for (String id : itemIds) {
                        JLabel lbl = labelMap.get(id);
                        if (lbl != null) {
                            int c = counts.getOrDefault(id, 0);
                            lbl.setText(id + ": " + c);
                        }
                    }
                });
            });
        } catch (RejectedExecutionException ignored) {
        }
    }
}
