package com.matterworks.core.ui.swing.panels;

import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.managers.GridManager;
import com.matterworks.core.ui.MariaDBAdapter;

import javax.swing.*;
import javax.swing.Timer;
import java.awt.*;
import java.util.List;
import java.util.*;
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

        // counts/unlock from heavy refresh
        volatile int lastCount = Integer.MIN_VALUE; // force first render
        volatile boolean unlocked = true;

        // IMPORTANT: cached unit price computed off-EDT (so fast refresh never hits DB)
        volatile double cachedUnitPrice = Double.NaN;      // NaN => not ready
        volatile long cachedUnitPriceRounded = Long.MIN_VALUE;

        // cache for fast refresh
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

        // Fast refresh (STRICTLY NO DB): prices (cached) + enable states
        fastPriceTimer = new Timer(450, e -> {
            if (disposed) return;
            if (!isDisplayable()) { dispose(); return; }
            if (!isShowing()) return;
            refreshPricesAndButtons();
        });
        fastPriceTimer.start();

        // Heavy refresh (DB): counts + unlock + (NOW) price computation in background
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
            if (r.lblBuyPrice != null) setVisibleIfChanged(r.lblBuyPrice, isPlayerView);
        }
    }

    private JPanel createItemRow(String itemId) {
        itemId = normalizeItemId(itemId);
        boolean tradeable = !NON_TRADEABLE.contains(itemId);

        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(350, 42));

        JLabel label = new JLabel(itemId + ": 0");
        label.setForeground(Color.WHITE);
        label.setFont(new Font("Monospaced", Font.PLAIN, 12));

        JPanel right = new JPanel();
        right.setOpaque(false);
        right.setLayout(new BoxLayout(right, BoxLayout.X_AXIS));

        JButton btnRem = new JButton("-");
        JButton btnAdd = new JButton("+");
        setupTinyButton(btnRem, new Color(140, 80, 80));
        setupTinyButton(btnAdd, new Color(80, 140, 80));

        JLabel price = null;
        if (tradeable) {
            price = new JLabel("...");
            price.setForeground(PENDING_GRAY);
            price.setFont(new Font("Monospaced", Font.BOLD, 12));
            price.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 6));
        }

        if (price != null) right.add(price);
        right.add(btnRem);
        right.add(Box.createHorizontalStrut(6));
        right.add(btnAdd);

        row.add(label, BorderLayout.CENTER);
        row.add(right, BorderLayout.EAST);

        RowUI ui = new RowUI(itemId, tradeable, label, btnRem, btnAdd, price);
        rows.put(itemId, ui);

        // Actions
        String finalItemId = itemId;
        btnAdd.addActionListener(e -> runActionAsync(btnAdd, () -> {
            gridManager.buyItem(playerUuid, finalItemId, 1);
        }));

        String finalItemId1 = itemId;
        btnRem.addActionListener(e -> runActionAsync(btnRem, () -> {
            repository.modifyInventoryItem(playerUuid, finalItemId1, -1);
        }));

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
                        // IMPORTANT: these must remain cheap on EDT.
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
     * Fast refresh (STRICTLY NO DB):
     * - uses cachedUnitPrice computed in requestCountsRefresh() background thread.
     * - updates only if values changed.
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

            double price = r.cachedUnitPrice; // cached: NO DB here
            boolean priceReady = !Double.isNaN(price) && !Double.isInfinite(price) && price >= 0.0;

            if (r.lblBuyPrice != null) {
                if (!r.unlocked) {
                    setTextIfChanged(r.lblBuyPrice, "LOCK");
                    setColorIfChanged(r.lblBuyPrice, LOCK_GRAY);
                } else if (!priceReady) {
                    setTextIfChanged(r.lblBuyPrice, "...");
                    setColorIfChanged(r.lblBuyPrice, PENDING_GRAY);
                } else {
                    boolean afford = isAdmin || money >= price;

                    long rounded = Math.round(price);
                    if (rounded != r.cachedUnitPriceRounded) {
                        setTextIfChanged(r.lblBuyPrice, "$" + String.format(Locale.US, "%d", rounded));
                        r.cachedUnitPriceRounded = rounded;
                    }

                    if (!moneySame || !adminSame || r.lastAfford == null || afford != r.lastAfford) {
                        setColorIfChanged(r.lblBuyPrice, afford ? BUY_GREEN : CANT_AFFORD_RED);
                        r.lastAfford = afford;
                    }

                    setEnabledIfChanged(r.btnAdd, r.unlocked && afford);
                    setEnabledIfChanged(r.btnRem, r.lastCount > 0);
                    continue;
                }
            }

            // fallback button state when price not ready
            setEnabledIfChanged(r.btnAdd, r.unlocked);
            setEnabledIfChanged(r.btnRem, r.lastCount > 0);
        }
    }

    /**
     * Heavy refresh (DB): inventory counts + unlock states + (NEW) price computation off-EDT.
     *
     * Key guarantee: price computation MUST NOT hit DB (it uses invCount already fetched).
     */
    private void requestCountsRefresh() {
        if (disposed) return;
        if (!isShowing() || !isDisplayable()) return;

        if (!countsRefreshRunning.compareAndSet(false, true)) return;

        try {
            countsExec.submit(() -> {
                try {
                    // 1) DB counts (off-EDT)
                    Map<String, Integer> counts = new HashMap<>();
                    for (String itemId : rows.keySet()) {
                        counts.put(itemId, repository.getInventoryItemCount(playerUuid, itemId));
                    }

                    // 2) Profile snapshot (cached, no DB)
                    PlayerProfile p = gridManager.getCachedProfile(playerUuid);
                    boolean isPlayerView = stableIsPlayerView(p);

                    // 3) Unlock states (no DB)
                    Map<String, Boolean> unlocked = new HashMap<>();
                    if (isPlayerView && p != null) {
                        for (String itemId : rows.keySet()) {
                            unlocked.put(itemId, gridManager.getTechManager().canBuyItem(p, itemId));
                        }
                    }

                    // 4) Prices OFF-EDT, NO DB: compute from known invCount
                    Map<String, Double> unitPrices = new HashMap<>();
                    if (isPlayerView && p != null) {
                        for (String itemId : rows.keySet()) {
                            int invCount = counts.getOrDefault(itemId, 0);
                            double unit = gridManager.getEffectiveShopUnitPrice(p, itemId, invCount);
                            unitPrices.put(itemId, unit);
                        }
                    }

                    SwingUtilities.invokeLater(() -> {
                        if (disposed || !isDisplayable()) return;

                        applyMode(isPlayerView);

                        for (RowUI r : rows.values()) {
                            int c = counts.getOrDefault(r.itemId, 0);

                            boolean countChanged = (c != r.lastCount);
                            r.lastCount = c;

                            // update cached price (even if not visible yet)
                            if (r.tradeable && isPlayerView && p != null) {
                                Double unit = unitPrices.get(r.itemId);
                                r.cachedUnitPrice = (unit != null ? unit : Double.NaN);
                                // do not update rounded here: refreshPricesAndButtons() handles formatting
                            } else {
                                r.cachedUnitPrice = Double.NaN;
                                r.cachedUnitPriceRounded = Long.MIN_VALUE;
                                r.lastAfford = null;
                            }

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