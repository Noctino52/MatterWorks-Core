package com.matterworks.core.ui.swing.panels;

import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.managers.GridManager;
import com.matterworks.core.ports.IRepository;

import javax.swing.*;
import javax.swing.Timer;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

public class InventoryDebugPanel extends JPanel {

    private static final List<String> FALLBACK_ITEMS = List.of(
            "drill_mk1",
            "conveyor_belt",
            "splitter",
            "merger",
            "lift",
            "dropper",
            "chromator",
            "color_mixer",
            "smoothing",
            "cutting",
            "shiny_polisher",
            "blazing_forge",
            "glitch_distorter",
            "nexus_core"
    );

    private static final Set<String> NON_TRADEABLE = Set.of("nexus_core");

    private final IRepository repository;
    private final UUID playerUuid;
    private final GridManager gridManager;
    private final Runnable onEconomyMaybeChanged;

    private final Map<String, RowUI> rows = new LinkedHashMap<>();

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mw-inventory-panel");
        t.setDaemon(true);
        return t;
    });

    private Timer refreshTimer;
    private volatile boolean disposed = false;

    private static final class RowUI {
        final String itemId;
        final JLabel label;
        final JButton btnRem;
        final JButton btnAdd;
        final boolean tradeable;

        RowUI(String itemId, JLabel label, JButton btnRem, JButton btnAdd, boolean tradeable) {
            this.itemId = itemId;
            this.label = label;
            this.btnRem = btnRem;
            this.btnAdd = btnAdd;
            this.tradeable = tradeable;
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

        for (String itemId : itemIds) {
            add(createItemRow(itemId, isPlayer));
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
        boolean tradeable = !NON_TRADEABLE.contains(itemId);

        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(350, 42));

        JLabel lblInfo = new JLabel(itemId + ": 0");
        lblInfo.setForeground(Color.WHITE);
        lblInfo.setFont(new Font("Monospaced", Font.BOLD, 12));

        JPanel buttons = new JPanel(new GridLayout(1, 2, 6, 0));
        buttons.setOpaque(false);
        buttons.setPreferredSize(new Dimension(90, 28));
        buttons.setMinimumSize(new Dimension(90, 28));

        JButton btnRem = new JButton("-");
        JButton btnAdd = new JButton("+");

        setupTinyButton(btnRem, new Color(120, 50, 50));
        setupTinyButton(btnAdd, new Color(50, 110, 50));

        // Nexus: niente +/- (solo visual)
        if (!tradeable) {
            btnRem.setVisible(false);
            btnAdd.setVisible(false);
            btnRem.setEnabled(false);
            btnAdd.setEnabled(false);

            buttons.add(btnRem);
            buttons.add(btnAdd);

            row.add(lblInfo, BorderLayout.CENTER);
            row.add(buttons, BorderLayout.EAST);

            rows.put(itemId, new RowUI(itemId, lblInfo, btnRem, btnAdd, false));
            return row;
        }

        btnAdd.addActionListener(e -> runAsyncButton(btnAdd, () -> {
            if (isPlayer) gridManager.buyItem(playerUuid, itemId, 1);
            else repository.modifyInventoryItem(playerUuid, itemId, 1);
        }, true));

        btnRem.addActionListener(e -> runAsyncButton(btnRem, () -> {
            if (isPlayer) {
                int have = repository.getInventoryItemCount(playerUuid, itemId);
                if (have > 0) {
                    double refund = gridManager.getEffectiveShopUnitPrice(playerUuid, itemId) * 0.5;
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

        rows.put(itemId, new RowUI(itemId, lblInfo, btnRem, btnAdd, true));
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

                Map<String, Integer> counts = new HashMap<>();
                for (String itemId : rows.keySet()) {
                    counts.put(itemId, repository.getInventoryItemCount(playerUuid, itemId));
                }

                PlayerProfile p = gridManager.getCachedProfile(playerUuid);
                double money = (p != null ? p.getMoney() : 0.0);
                boolean isAdmin = (p != null && p.isAdmin());

                Map<String, Boolean> canBuy = new HashMap<>();
                Map<String, Boolean> canAfford = new HashMap<>();
                Map<String, Double> pricesNow = new HashMap<>();

                if (isPlayer && p != null) {
                    for (String itemId : rows.keySet()) {
                        boolean okTech = gridManager.getTechManager().canBuyItem(p, itemId);
                        canBuy.put(itemId, okTech);

                        double priceNow = gridManager.getEffectiveShopUnitPrice(p, itemId);
                        pricesNow.put(itemId, priceNow);

                        boolean affordNow = isAdmin || money >= priceNow;
                        canAfford.put(itemId, affordNow);
                    }
                }

                SwingUtilities.invokeLater(() -> {
                    if (disposed || !isDisplayable()) return;

                    for (RowUI r : rows.values()) {
                        int c = counts.getOrDefault(r.itemId, 0);

                        if (!isPlayer) {
                            r.label.setText(r.itemId + ": " + c);
                            if (r.tradeable) {
                                r.btnAdd.setEnabled(true);
                                r.btnRem.setEnabled(true);
                            }
                            continue;
                        }

                        boolean unlocked = canBuy.getOrDefault(r.itemId, false);
                        boolean afford = canAfford.getOrDefault(r.itemId, true);
                        double priceNow = pricesNow.getOrDefault(r.itemId, 0.0);

                        String suffix = unlocked ? "" : "  [LOCKED]";
                        r.label.setText(r.itemId + ": " + c + suffix);

                        if (!r.tradeable) {
                            r.btnAdd.setEnabled(false);
                            r.btnRem.setEnabled(false);
                            continue;
                        }

                        r.btnAdd.setEnabled(unlocked && afford);

                        if (!unlocked) r.btnAdd.setToolTipText("Locked: unlock via Tech Tree");
                        else r.btnAdd.setToolTipText("Buy for $" + String.format(Locale.US, "%.0f", priceNow));

                        r.label.setToolTipText("Price: $" + String.format(Locale.US, "%.0f", priceNow));
                        r.btnRem.setEnabled(c > 0);
                    }
                });
            });
        } catch (RejectedExecutionException ignored) {}
    }
}
