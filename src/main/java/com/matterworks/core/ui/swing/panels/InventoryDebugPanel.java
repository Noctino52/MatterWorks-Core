package com.matterworks.core.ui.swing.panels;

import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.managers.GridManager;
import com.matterworks.core.ui.MariaDBAdapter;

import javax.swing.*;
import javax.swing.Timer;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class InventoryDebugPanel extends JPanel {

    private static final List<String> FALLBACK_ITEMS = List.of(
            "drill",
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

    private static final Color BUY_GREEN = new Color(70, 210, 90);
    private static final Color LOCK_GRAY = new Color(170, 170, 170);
    private static final Color CANT_AFFORD_RED = new Color(220, 90, 90);
    private static final Color PENDING_GRAY = new Color(140, 140, 140);

    private final MariaDBAdapter repository;
    private final UUID playerUuid;
    private final GridManager gridManager;
    private final Runnable onEconomyMaybeChanged;

    private final Map<String, RowUI> rows = new LinkedHashMap<>();

    // Separate: (+/-) actions and count refreshes
    private final ExecutorService actionExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mw-inventory-actions");
        t.setDaemon(true);
        return t;
    });

    private final ExecutorService countsExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mw-inventory-counts");
        t.setDaemon(true);
        return t;
    });

    // Prevent infinite queue of heavy refreshes
    private final AtomicBoolean countsRefreshRunning = new AtomicBoolean(false);

    // Light timer: prices + buttons enable (no DB)
    private Timer fastPriceTimer;

    // Heavy timer: inventory counts + unlock (DB)
    private Timer countsTimer;

    private volatile boolean disposed = false;

    // Used to update the border title when switching "mode"
    private volatile Boolean lastPlayerView = null;

    private static final class RowUI {
        final String itemId;
        final boolean tradeable;

        final JLabel label;
        final JButton btnRem;
        final JButton btnAdd;
        final JLabel lblBuyPrice;

        volatile int lastCount = 0;
        volatile boolean unlocked = true;

        RowUI(String itemId, boolean tradeable, JLabel label, JButton btnRem, JButton btnAdd, JLabel lblBuyPrice) {
            this.itemId = itemId;
            this.tradeable = tradeable;
            this.label = label;
            this.btnRem = btnRem;
            this.btnAdd = btnAdd;
            this.lblBuyPrice = lblBuyPrice;
        }
    }

    public InventoryDebugPanel(MariaDBAdapter repository, UUID playerUuid, GridManager gm, Runnable onEconomyMaybeChanged) {
        this.repository = repository;
        this.playerUuid = playerUuid;
        this.gridManager = gm;
        this.onEconomyMaybeChanged = onEconomyMaybeChanged;

        this.setPreferredSize(new Dimension(360, 0));
        this.setMinimumSize(new Dimension(360, 0));

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(new Color(40, 40, 40));

        // Temporary border (updated dynamically)
        setBorder(BorderFactory.createTitledBorder("Warehouse"));

        List<String> itemIds = gridManager.getBlockRegistry().getShopMachineIdsFromDb();
        if (itemIds == null || itemIds.isEmpty()) itemIds = FALLBACK_ITEMS;

        // Normalize + preserve order + avoid duplicates
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String id : itemIds) unique.add(normalizeItemId(id));

        for (String itemId : unique) {
            add(createItemRow(itemId));
            add(Box.createVerticalStrut(8));
        }

        // Immediate and frequent refresh
        fastPriceTimer = new Timer(200, e -> refreshPricesAndButtons());
        fastPriceTimer.start();

        // Heavy refresh less frequent
        countsTimer = new Timer(900, e -> requestCountsRefresh());
        countsTimer.start();

        // Initial refresh
        refreshPricesAndButtons();
        requestCountsRefresh();
    }

    public void dispose() {
        disposed = true;
        try { if (fastPriceTimer != null) fastPriceTimer.stop(); } catch (Exception ignored) {}
        try { if (countsTimer != null) countsTimer.stop(); } catch (Exception ignored) {}
        try { actionExec.shutdownNow(); } catch (Exception ignored) {}
        try { countsExec.shutdownNow(); } catch (Exception ignored) {}
    }

    private static String normalizeItemId(String itemId) {
        if (itemId == null) return null;
        // Hard rename: drill -> drill (defensive UI normalization)
        if ("drill".equals(itemId)) return "drill";
        return itemId;
    }

    private boolean computeIsPlayerView(PlayerProfile p) {
        return p != null && p.getRank() == PlayerProfile.PlayerRank.PLAYER;
    }

    private void ensureBorderForMode(boolean isPlayerView) {
        if (lastPlayerView != null && lastPlayerView == isPlayerView) return;
        lastPlayerView = isPlayerView;

        setBorder(BorderFactory.createTitledBorder(isPlayerView ? "Warehouse Shop" : "Warehouse Monitor"));
        revalidate();
        repaint();
    }

    private JPanel createItemRow(String itemId) {
        itemId = normalizeItemId(itemId);
        boolean tradeable = !NON_TRADEABLE.contains(itemId);

        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(350, 42));

        JLabel lblInfo = new JLabel(itemId + ": 0");
        lblInfo.setForeground(Color.WHITE);
        lblInfo.setFont(new Font("Monospaced", Font.BOLD, 12));

        JButton btnRem = new JButton("-");
        JButton btnAdd = new JButton("+");

        setupTinyButton(btnRem, new Color(120, 50, 50));
        setupTinyButton(btnAdd, new Color(50, 110, 50));

        // Price label (hidden in monitor mode during refresh)
        JLabel lblBuyPrice = new JLabel("...");
        lblBuyPrice.setFont(new Font("SansSerif", Font.PLAIN, 10));
        lblBuyPrice.setForeground(PENDING_GRAY);
        lblBuyPrice.setPreferredSize(new Dimension(55, 18));
        lblBuyPrice.setMinimumSize(new Dimension(55, 18));

        JPanel east = new JPanel();
        east.setOpaque(false);
        east.setLayout(new BoxLayout(east, BoxLayout.X_AXIS));
        east.add(btnRem);
        east.add(Box.createHorizontalStrut(6));
        east.add(btnAdd);
        east.add(Box.createHorizontalStrut(6));
        east.add(lblBuyPrice);

        // Nexus (or other future items) can be non-tradeable
        if (!tradeable) {
            btnRem.setVisible(false);
            btnAdd.setVisible(false);
            lblBuyPrice.setVisible(false);

            row.add(lblInfo, BorderLayout.CENTER);
            row.add(east, BorderLayout.EAST);

            rows.put(itemId, new RowUI(itemId, false, lblInfo, btnRem, btnAdd, lblBuyPrice));
            return row;
        }

        final String finalItemId = itemId;

        btnAdd.addActionListener(e -> runActionAsync(btnAdd, () -> {
            PlayerProfile p = gridManager.getCachedProfile(playerUuid);
            boolean isPlayerView = computeIsPlayerView(p);

            if (isPlayerView) gridManager.buyItem(playerUuid, finalItemId, 1);
            else repository.modifyInventoryItem(playerUuid, finalItemId, 1);
        }));

        btnRem.addActionListener(e -> runActionAsync(btnRem, () -> {
            PlayerProfile p = gridManager.getCachedProfile(playerUuid);
            boolean isPlayerView = computeIsPlayerView(p);

            if (isPlayerView) {
                int have = repository.getInventoryItemCount(playerUuid, finalItemId);
                if (have > 0) {
                    // Refund 50% of current price
                    double refund = gridManager.getEffectiveShopUnitPrice(playerUuid, finalItemId) * 0.5;
                    gridManager.addMoney(playerUuid, refund, "ITEM_SELL", finalItemId);
                    repository.modifyInventoryItem(playerUuid, finalItemId, -1);
                }
            } else {
                repository.modifyInventoryItem(playerUuid, finalItemId, -1);
            }
        }));

        row.add(lblInfo, BorderLayout.CENTER);
        row.add(east, BorderLayout.EAST);

        rows.put(itemId, new RowUI(itemId, true, lblInfo, btnRem, btnAdd, lblBuyPrice));
        return row;
    }

    private void setupTinyButton(JButton b, Color bg) {
        b.setPreferredSize(new Dimension(46, 28));
        b.setMinimumSize(new Dimension(46, 28));
        b.setMaximumSize(new Dimension(46, 28));
        b.setMargin(new Insets(0, 0, 0, 0));
        b.setFont(new Font("SansSerif", Font.BOLD, 15));
        b.setFocusable(false);
        b.setForeground(Color.WHITE);
        b.setBackground(bg);
    }

    private void runActionAsync(JButton btn, Runnable action) {
        if (disposed) return;

        btn.setEnabled(false);

        try {
            actionExec.submit(() -> {
                try {
                    action.run();
                } catch (Throwable t) {
                    t.printStackTrace();
                } finally {
                    SwingUtilities.invokeLater(this::refreshPricesAndButtons);
                    requestCountsRefresh();

                    if (onEconomyMaybeChanged != null) {
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

    /**
     * Immediate refresh (NO DB): prices + button enable states.
     * Player/monitor mode is evaluated dynamically.
     */
    private void refreshPricesAndButtons() {
        if (disposed) return;
        if (!isShowing() || !isDisplayable()) return;

        PlayerProfile p = gridManager.getCachedProfile(playerUuid);
        boolean isPlayerView = computeIsPlayerView(p);
        ensureBorderForMode(isPlayerView);

        // Monitor mode: hide prices, enable buttons
        if (!isPlayerView) {
            for (RowUI r : rows.values()) {
                if (!r.tradeable) continue;
                r.btnAdd.setEnabled(true);
                r.btnRem.setEnabled(true);
                if (r.lblBuyPrice != null) r.lblBuyPrice.setVisible(false);
            }
            return;
        }

        // Shop mode: profile still loading
        if (p == null) {
            for (RowUI r : rows.values()) {
                if (!r.tradeable) continue;

                if (r.lblBuyPrice != null) {
                    r.lblBuyPrice.setVisible(true);
                    r.lblBuyPrice.setText("...");
                    r.lblBuyPrice.setForeground(PENDING_GRAY);
                }

                r.btnAdd.setEnabled(r.unlocked);
                r.btnRem.setEnabled(r.lastCount > 0);
            }
            return;
        }

        double money = p.getMoney();
        boolean isAdmin = p.isAdmin();

        for (RowUI r : rows.values()) {
            if (!r.tradeable) continue;

            double price = gridManager.getEffectiveShopUnitPrice(p, r.itemId);

            if (r.lblBuyPrice != null) {
                r.lblBuyPrice.setVisible(true);

                if (!r.unlocked) {
                    r.lblBuyPrice.setText("LOCK");
                    r.lblBuyPrice.setForeground(LOCK_GRAY);
                } else {
                    r.lblBuyPrice.setText("$" + String.format(Locale.US, "%.0f", price));
                    boolean afford = isAdmin || money >= price;
                    r.lblBuyPrice.setForeground(afford ? BUY_GREEN : CANT_AFFORD_RED);
                }
            }

            boolean afford = isAdmin || money >= price;
            r.btnAdd.setEnabled(r.unlocked && afford);
            r.btnRem.setEnabled(r.lastCount > 0);
        }
    }

    /**
     * Heavy refresh (DB): inventory counts + unlock states.
     * Does not queue: if already running, it skips.
     */
    private void requestCountsRefresh() {
        if (disposed) return;
        if (!isShowing() || !isDisplayable()) return;

        if (!countsRefreshRunning.compareAndSet(false, true)) return;

        try {
            countsExec.submit(() -> {
                try {
                    Map<String, Integer> counts = new HashMap<>();
                    for (String itemId : rows.keySet()) {
                        counts.put(itemId, repository.getInventoryItemCount(playerUuid, itemId));
                    }

                    PlayerProfile p = gridManager.getCachedProfile(playerUuid);
                    boolean isPlayerView = computeIsPlayerView(p);

                    Map<String, Boolean> unlocked = new HashMap<>();
                    if (isPlayerView && p != null) {
                        for (String itemId : rows.keySet()) {
                            unlocked.put(itemId, gridManager.getTechManager().canBuyItem(p, itemId));
                        }
                    }

                    SwingUtilities.invokeLater(() -> {
                        if (disposed || !isDisplayable()) return;

                        ensureBorderForMode(isPlayerView);

                        for (RowUI r : rows.values()) {
                            int c = counts.getOrDefault(r.itemId, 0);
                            r.lastCount = c;

                            if (!r.tradeable) {
                                r.label.setText(r.itemId + ": " + c);
                                continue;
                            }

                            if (isPlayerView) {
                                boolean isUnlocked = unlocked.getOrDefault(r.itemId, r.unlocked);
                                r.unlocked = isUnlocked;
                                String suffix = isUnlocked ? "" : "  [LOCKED]";
                                r.label.setText(r.itemId + ": " + c + suffix);
                            } else {
                                r.label.setText(r.itemId + ": " + c);
                            }
                        }

                        refreshPricesAndButtons();
                    });

                } catch (Throwable t) {
                    t.printStackTrace();
                } finally {
                    countsRefreshRunning.set(false);
                }
            });
        } catch (RejectedExecutionException ex) {
            countsRefreshRunning.set(false);
        }
    }
}
