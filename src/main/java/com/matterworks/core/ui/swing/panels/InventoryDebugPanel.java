// FILE: src/main/java/com/matterworks/core/infrastructure/swing/InventoryDebugPanel.java
package com.matterworks.core.ui.swing.panels;

import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.managers.GridManager;
import com.matterworks.core.ports.IRepository;

import javax.swing.*;
import javax.swing.Timer;
import java.awt.*;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;

public class InventoryDebugPanel extends JPanel {

    private final IRepository repository;
    private final UUID playerUuid;
    private final GridManager gridManager;
    private final Runnable onEconomyMaybeChanged;

    private final Map<String, RowUI> rows = new LinkedHashMap<>();
    private final Timer refreshTimer;

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mw-inventory-panel-worker");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean disposed = false;

    // fallback se DB non ha ancora definizioni (non rompo nulla)
    private static final List<String> FALLBACK_ITEMS = List.of(
            "drill_mk1",
            "conveyor_belt",
            "splitter",
            "merger",
            "lift",
            "dropper",
            "nexus_core",
            "chromator",
            "color_mixer",
            "smoothing",
            "cutting",
            // nuovi effetti
            "shiny_polisher",
            "blazing_forge",
            "glitch_distorter"
    );

    private static final class RowUI {
        final String itemId;
        final JLabel label;
        final JButton btnRem;
        final JButton btnAdd;
        final double price;

        RowUI(String itemId, JLabel label, JButton btnRem, JButton btnAdd, double price) {
            this.itemId = itemId;
            this.label = label;
            this.btnRem = btnRem;
            this.btnAdd = btnAdd;
            this.price = price;
        }
    }

    public InventoryDebugPanel(IRepository repository, UUID playerUuid, GridManager gm, Runnable onEconomyMaybeChanged) {
        this.repository = repository;
        this.playerUuid = playerUuid;
        this.gridManager = gm;
        this.onEconomyMaybeChanged = onEconomyMaybeChanged;

        this.setPreferredSize(new Dimension(360, 0));
        this.setMinimumSize(new Dimension(360, 0));

        PlayerProfile profile = gridManager.getCachedProfile(playerUuid);
        boolean isPlayer = (profile != null && profile.getRank() == PlayerProfile.PlayerRank.PLAYER);

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createTitledBorder(isPlayer ? "Warehouse Shop" : "Warehouse Monitor"));
        setBackground(new Color(40, 40, 40));

        List<String> itemIds = gridManager.getBlockRegistry().getShopMachineIdsFromDb();
        if (itemIds == null || itemIds.isEmpty()) itemIds = FALLBACK_ITEMS;

        // costruzione righe
        for (String id : itemIds) {
            add(createItemRow(id, isPlayer));
            add(Box.createVerticalStrut(8));
        }

        refreshTimer = new Timer(500, e -> requestRefresh(isPlayer));
        refreshTimer.start();

        requestRefresh(isPlayer);
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
        row.setMaximumSize(new Dimension(350, 42));

        double price = gridManager.getBlockRegistry().getPrice(itemId);

        JLabel lblInfo = new JLabel(itemId + ": 0");
        lblInfo.setForeground(Color.WHITE);
        lblInfo.setFont(new Font("Monospaced", Font.BOLD, 12));

        if (isPlayer) {
            lblInfo.setToolTipText("Price: $" + price);
        }

        JPanel buttons = new JPanel(new GridLayout(1, 2, 6, 0));
        buttons.setOpaque(false);
        buttons.setPreferredSize(new Dimension(90, 28));
        buttons.setMinimumSize(new Dimension(90, 28));

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

        rows.put(itemId, new RowUI(itemId, lblInfo, btnRem, btnAdd, price));
        return row;
    }

    private void setupTinyButton(JButton b, Color bg) {
        b.setPreferredSize(new Dimension(40, 25));
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
                    requestRefresh(true);
                    if (economyChanged && onEconomyMaybeChanged != null) {
                        SwingUtilities.invokeLater(onEconomyMaybeChanged);
                    }
                    SwingUtilities.invokeLater(() -> {
                        if (btn.isDisplayable()) btn.setEnabled(true);
                    });
                }
            });
        } catch (RejectedExecutionException ex) {
            SwingUtilities.invokeLater(() -> {
                if (btn.isDisplayable()) btn.setEnabled(true);
            });
        }
    }

    private void requestRefresh(boolean isPlayer) {
        if (disposed) return;
        if (!isShowing()) return;

        try {
            exec.submit(() -> {
                // snapshot counts
                Map<String, Integer> counts = new HashMap<>();
                for (String id : rows.keySet()) {
                    counts.put(id, repository.getInventoryItemCount(playerUuid, id));
                }

                PlayerProfile p = gridManager.getCachedProfile(playerUuid);
                double money = (p != null ? p.getMoney() : 0.0);
                boolean isAdmin = (p != null && p.isAdmin());

                // snapshot lock/afford
                Map<String, Boolean> canBuy = new HashMap<>();
                Map<String, Boolean> canAfford = new HashMap<>();

                if (isPlayer && p != null) {
                    for (String id : rows.keySet()) {
                        boolean okTech = gridManager.getTechManager().canBuyItem(p, id);
                        canBuy.put(id, okTech);
                        canAfford.put(id, isAdmin || money >= rows.get(id).price);
                    }
                }

                SwingUtilities.invokeLater(() -> {
                    if (disposed || !isDisplayable()) return;

                    for (RowUI r : rows.values()) {
                        int c = counts.getOrDefault(r.itemId, 0);

                        if (!isPlayer) {
                            r.label.setText(r.itemId + ": " + c);
                            r.btnAdd.setEnabled(true);
                            r.btnRem.setEnabled(true);
                            continue;
                        }

                        boolean unlocked = canBuy.getOrDefault(r.itemId, false);
                        boolean afford = canAfford.getOrDefault(r.itemId, true);

                        String suffix = unlocked ? "" : "  [LOCKED]";
                        r.label.setText(r.itemId + ": " + c + suffix);

                        // Buy enabled solo se tech ok e soldi ok
                        r.btnAdd.setEnabled(unlocked && afford);

                        if (!unlocked) {
                            r.btnAdd.setToolTipText("Locked: unlock via Tech Tree");
                        } else {
                            r.btnAdd.setToolTipText("Buy for $" + r.price);
                        }

                        // Sell: abilito se hai almeno 1
                        r.btnRem.setEnabled(c > 0);
                    }
                });
            });
        } catch (RejectedExecutionException ignored) {
        }
    }
}
