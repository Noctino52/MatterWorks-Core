package com.matterworks.core.ui.swing.panels;

import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.managers.GridManager;
import com.matterworks.core.ui.MariaDBAdapter;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
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

    private final AtomicBoolean countsRefreshRunning = new AtomicBoolean(false);

    private Timer fastPriceTimer;
    private Timer countsTimer;

    private volatile boolean disposed = false;

    // IMPORTANT: mode must be stable across profile null phases during switching
    private volatile Boolean lastIsPlayerView = null;

    // Cache to reduce pointless recalcs
    private volatile double lastMoney = Double.NaN;
    private volatile boolean lastIsAdmin = false;

    // Header label instead of changing border/title (prevents repaint storms)
    private final JLabel headerLabel = new JLabel("Warehouse");

    private static final class RowUI {
        final String itemId;
        final boolean tradeable;

        final JLabel label;
        final JButton btnRem;
        final JButton btnAdd;
        final JLabel lblBuyPrice;

        volatile int lastCount = Integer.MIN_VALUE; // force first render
        volatile boolean unlocked = true;

        // cache for fast refresh
        volatile long lastShownPriceRounded = Long.MIN_VALUE;
        volatile Boolean lastAfford = null;

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

        setPreferredSize(new Dimension(360, 0));
        setMinimumSize(new Dimension(360, 0));
        setBackground(new Color(40, 40, 40));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        setLayout(new BorderLayout());

        // Header (fixed component)
        headerLabel.setForeground(Color.WHITE);
        headerLabel.setFont(new Font("Monospaced", Font.BOLD, 14));
        headerLabel.setBorder(BorderFactory.createEmptyBorder(2, 2, 10, 2));
        add(headerLabel, BorderLayout.NORTH);

        // Content list
        JPanel list = new JPanel();
        list.setOpaque(false);
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        add(list, BorderLayout.CENTER);

        List<String> itemIds = gridManager.getBlockRegistry().getShopMachineIdsFromDb();
        if (itemIds == null || itemIds.isEmpty()) itemIds = FALLBACK_ITEMS;

        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String id : itemIds) unique.add(normalizeItemId(id));

        for (String itemId : unique) {
            list.add(createItemRow(itemId));
            list.add(Box.createVerticalStrut(8));
        }
        list.add(Box.createVerticalGlue());

        // Fast refresh (NO DB): prices + enable states
        fastPriceTimer = new Timer(450, e -> {
            if (disposed) return;
            if (!isDisplayable()) { dispose(); return; }
            if (!isShowing()) return;
            refreshPricesAndButtons();
        });
        fastPriceTimer.start();

        // Heavy refresh (DB): counts + unlock
        countsTimer = new Timer(1500, e -> {
            if (disposed) return;
            if (!isDisplayable()) { dispose(); return; }
            if (!isShowing()) return;
            requestCountsRefresh();
        });
        countsTimer.start();

        // Initial
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
        return itemId;
    }

    private boolean computeIsPlayerView(PlayerProfile p) {
        return p != null && p.getRank() == PlayerProfile.PlayerRank.PLAYER;
    }

    /**
     * IMPORTANT: If profile is null (during switching), do NOT flip the mode.
     * Keep the last known mode to avoid UI flip-flop and repaint storms.
     */
    private boolean stableIsPlayerView(PlayerProfile p) {
        if (p == null) {
            // default to last known; if unknown, assume player view (shop)
            return lastIsPlayerView != null ? lastIsPlayerView : true;
        }
        return computeIsPlayerView(p);
    }

    /**
     * Header update (cheap). Also toggles price visibility ONCE per mode change.
     */
    private void applyMode(boolean isPlayerView) {
        if (lastIsPlayerView != null && lastIsPlayerView == isPlayerView) return;
        lastIsPlayerView = isPlayerView;

        String title = isPlayerView ? "Warehouse Shop" : "Warehouse Monitor";
        setTextIfChanged(headerLabel, title);

        // Show/hide prices ONLY when mode changes (not every tick)
        for (RowUI r : rows.values()) {
            if (!r.tradeable) continue;
            if (r.lblBuyPrice != null) {
                setVisibleIfChanged(r.lblBuyPrice, isPlayerView);
            }
        }
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

        final String finalItemId = itemId;

        if (!tradeable) {
            btnRem.setVisible(false);
            btnAdd.setVisible(false);
            lblBuyPrice.setVisible(false);

            row.add(lblInfo, BorderLayout.CENTER);
            row.add(east, BorderLayout.EAST);

            rows.put(finalItemId, new RowUI(finalItemId, false, lblInfo, btnRem, btnAdd, lblBuyPrice));
            return row;
        }

        btnAdd.addActionListener(e -> runActionAsync(btnAdd, () -> {
            PlayerProfile p = gridManager.getCachedProfile(playerUuid);
            boolean isPlayerView = stableIsPlayerView(p);

            if (isPlayerView) gridManager.buyItem(playerUuid, finalItemId, 1);
            else repository.modifyInventoryItem(playerUuid, finalItemId, 1);
        }));

        btnRem.addActionListener(e -> runActionAsync(btnRem, () -> {
            PlayerProfile p = gridManager.getCachedProfile(playerUuid);
            boolean isPlayerView = stableIsPlayerView(p);

            if (isPlayerView) {
                int have = repository.getInventoryItemCount(playerUuid, finalItemId);
                if (have > 0) {
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

        rows.put(finalItemId, new RowUI(finalItemId, true, lblInfo, btnRem, btnAdd, lblBuyPrice));
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

        setEnabledIfChanged(btn, false);

        try {
            actionExec.submit(() -> {
                try {
                    action.run();
                } catch (Throwable t) {
                    t.printStackTrace();
                } finally {
                    SwingUtilities.invokeLater(() -> {
                        refreshPricesAndButtons();
                        requestCountsRefresh();
                        if (onEconomyMaybeChanged != null) onEconomyMaybeChanged.run();
                        if (btn.isDisplayable()) setEnabledIfChanged(btn, true);
                    });
                }
            });
        } catch (RejectedExecutionException ex) {
            SwingUtilities.invokeLater(() -> {
                if (btn.isDisplayable()) setEnabledIfChanged(btn, true);
            });
        }
    }

    /**
     * Fast refresh (NO DB): prices + button enable states.
     * Optimized: updates only if values actually changed.
     */
    private void refreshPricesAndButtons() {
        if (disposed) return;
        if (!isShowing() || !isDisplayable()) return;

        PlayerProfile p = gridManager.getCachedProfile(playerUuid);
        boolean isPlayerView = stableIsPlayerView(p);
        applyMode(isPlayerView);

        // Monitor mode: buttons enabled, no prices
        if (!isPlayerView) {
            for (RowUI r : rows.values()) {
                if (!r.tradeable) continue;
                setEnabledIfChanged(r.btnAdd, true);
                setEnabledIfChanged(r.btnRem, true);
            }
            return;
        }

        // Player view but profile still loading
        if (p == null) {
            for (RowUI r : rows.values()) {
                if (!r.tradeable) continue;

                if (r.lblBuyPrice != null) {
                    setTextIfChanged(r.lblBuyPrice, "...");
                    setColorIfChanged(r.lblBuyPrice, PENDING_GRAY);
                }
                setEnabledIfChanged(r.btnAdd, r.unlocked);
                setEnabledIfChanged(r.btnRem, r.lastCount > 0);
            }
            return;
        }

        double money = p.getMoney();
        boolean isAdmin = p.isAdmin();

        boolean moneySame = Double.compare(money, lastMoney) == 0;
        boolean adminSame = isAdmin == lastIsAdmin;

        lastMoney = money;
        lastIsAdmin = isAdmin;

        for (RowUI r : rows.values()) {
            if (!r.tradeable) continue;

            double price = gridManager.getEffectiveShopUnitPrice(p, r.itemId);
            boolean afford = isAdmin || money >= price;

            if (r.lblBuyPrice != null) {
                if (!r.unlocked) {
                    setTextIfChanged(r.lblBuyPrice, "LOCK");
                    setColorIfChanged(r.lblBuyPrice, LOCK_GRAY);
                } else {
                    long rounded = Math.round(price);
                    if (rounded != r.lastShownPriceRounded) {
                        setTextIfChanged(r.lblBuyPrice, "$" + String.format(Locale.US, "%d", rounded));
                        r.lastShownPriceRounded = rounded;
                    }

                    if (!moneySame || !adminSame || r.lastAfford == null || afford != r.lastAfford) {
                        setColorIfChanged(r.lblBuyPrice, afford ? BUY_GREEN : CANT_AFFORD_RED);
                        r.lastAfford = afford;
                    }
                }
            }

            setEnabledIfChanged(r.btnAdd, r.unlocked && afford);
            setEnabledIfChanged(r.btnRem, r.lastCount > 0);
        }
    }

    /**
     * Heavy refresh (DB): inventory counts + unlock states.
     * Optimized: no queue buildup, apply only changed label text/unlock flags.
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
                    boolean isPlayerView = stableIsPlayerView(p);

                    Map<String, Boolean> unlocked = new HashMap<>();
                    if (isPlayerView && p != null) {
                        for (String itemId : rows.keySet()) {
                            unlocked.put(itemId, gridManager.getTechManager().canBuyItem(p, itemId));
                        }
                    }

                    SwingUtilities.invokeLater(() -> {
                        if (disposed || !isDisplayable()) return;

                        applyMode(isPlayerView);

                        for (RowUI r : rows.values()) {
                            int c = counts.getOrDefault(r.itemId, 0);

                            boolean countChanged = (c != r.lastCount);
                            r.lastCount = c;

                            if (!r.tradeable) {
                                if (countChanged) setTextIfChanged(r.label, r.itemId + ": " + c);
                                continue;
                            }

                            if (isPlayerView) {
                                boolean isUnlocked = unlocked.getOrDefault(r.itemId, r.unlocked);
                                boolean unlockChanged = (isUnlocked != r.unlocked);
                                r.unlocked = isUnlocked;

                                if (countChanged || unlockChanged) {
                                    String suffix = isUnlocked ? "" : "  [LOCKED]";
                                    setTextIfChanged(r.label, r.itemId + ": " + c + suffix);
                                }
                            } else {
                                if (countChanged) setTextIfChanged(r.label, r.itemId + ": " + c);
                            }
                        }

                        // Keep button states consistent (cheap)
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

    // ====== tiny helpers to avoid repaint storms ======

    private static void setTextIfChanged(JLabel lbl, String txt) {
        if (lbl == null) return;
        if (Objects.equals(lbl.getText(), txt)) return;
        lbl.setText(txt);
    }

    private static void setEnabledIfChanged(JButton b, boolean enabled) {
        if (b == null) return;
        if (b.isEnabled() == enabled) return;
        b.setEnabled(enabled);
    }

    private static void setVisibleIfChanged(JLabel lbl, boolean visible) {
        if (lbl == null) return;
        if (lbl.isVisible() == visible) return;
        lbl.setVisible(visible);
    }

    private static void setColorIfChanged(JLabel lbl, Color c) {
        if (lbl == null) return;
        if (Objects.equals(lbl.getForeground(), c)) return;
        lbl.setForeground(c);
    }
}
