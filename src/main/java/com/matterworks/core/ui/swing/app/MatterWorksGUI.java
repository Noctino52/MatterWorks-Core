package com.matterworks.core.ui.swing.app;

import com.matterworks.core.domain.machines.base.PlacedMachine;
import com.matterworks.core.domain.machines.registry.BlockRegistry;
import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.managers.GridManager;
import com.matterworks.core.ui.MariaDBAdapter;
import com.matterworks.core.ui.ServerConfig;
import com.matterworks.core.ui.swing.factory.FactoryPanel;
import com.matterworks.core.ui.swing.panels.InventoryDebugPanel;
import com.matterworks.core.ui.swing.panels.StatusBarPanel;
import com.matterworks.core.ui.swing.panels.TechTreePanel;
import com.matterworks.core.ui.swing.panels.TopBarPanel;
import com.matterworks.core.ui.swing.panels.VoidShopPanel;
import com.matterworks.core.domain.player.BoosterStatus;
import com.matterworks.core.ui.swing.panels.ProductionPanel;
import com.matterworks.core.ui.swing.panels.FactionsPanel;





import javax.swing.*;
import javax.swing.Timer;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.Method;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MatterWorksGUI extends JFrame {

    private final GridManager gridManager;
    private final BlockRegistry registry;
    private final MariaDBAdapter repository;
    private final Runnable onSave;

    private final FactoryPanel factoryPanel;
    private final JTabbedPane rightTabbedPane = new JTabbedPane();
    private final JPanel glassPane;

    private final TopBarPanel topBar;
    private final StatusBarPanel statusBar = new StatusBarPanel();

    private final Timer economyTimer;
    private final Timer heartbeatTimer;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mw-gui-worker");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean economyUpdateInFlight = new AtomicBoolean(false);
    private volatile EconomyUiState lastEconomyUiState = null;

    // Cached fallback (loaded off-EDT) for "items placed" count.
    private volatile int cachedPlacedItemsFromDb = -1;

    // ===== Economy UI refresh coalescing (EDT) =====
    private static final int ECONOMY_UI_DEBOUNCE_MS = 120;

    private javax.swing.Timer economyUiDebounceTimer;
    private volatile boolean economyUiDirty = false;
    private volatile long economyUiLastRequestAtMs = 0L;
    private volatile long economyUiLastApplyAtMs = 0L;

    // Cached DB-only count for tooltip/debug (never query DB on EDT)
    private volatile Integer cachedDbPlacedItems = null;
    private volatile long cachedDbPlacedItemsAtMs = 0L;

    private javax.swing.Timer economyUiTimer;
    private volatile boolean economyComputeInFlight = false;
    private volatile EconomyUiState lastAppliedEconomyState = null;




    // =========================
// Replace the EconomyUiState record definition with this one
// =========================
    private record EconomyUiState(
            boolean prestigeEnabled,
            String prestigeTooltip,
            boolean instantPrestigeEnabled,
            String moneyText,
            String roleText,
            String voidText,
            String prestigeText,
            String plotIdText,

            // NEW: numeric values to avoid parsing strings like "ITEMS: x / y"
            int placedItems,
            int itemCap,

            String plotAreaText,
            boolean plotMinusEnabled,
            boolean plotPlusEnabled,
            boolean itemCapPlusEnabled
    ) {}



    private UUID currentPlayerUuid;
    private List<PlayerProfile> cachedPlayerList = new ArrayList<>();

    private volatile boolean isSwitching = false;
    private volatile boolean suppressPlayerEvents = false;

    // --- UI caches (to reduce repaint) ---
    private volatile double lastMoneyShown = Double.NaN;
    private volatile String lastRankShown = null;
    private volatile Long lastPlotIdShown = null;
    private volatile boolean lastAdminShown = false;
    private volatile int lastVoidCoinsShown = Integer.MIN_VALUE;
    private volatile int lastPrestigeShown = Integer.MIN_VALUE;

    private volatile int lastPlotItemsShown = Integer.MIN_VALUE;
    private volatile int lastPlotCapShown = Integer.MIN_VALUE;

    private volatile String lastPlotAreaShown = null;
    private volatile boolean lastPlotPlusEnabled = false;
    private volatile boolean lastPlotMinusEnabled = false;

    // NEW: cache per enable del bottone item cap "+"
    private volatile boolean lastItemCapPlusEnabled = false;

    // ===== Economy DB cache (to avoid blocking EDT) =====
    private final AtomicBoolean economyFetchRunning = new AtomicBoolean(false);
    private volatile UUID economyCacheUuid = null;
    private volatile long economyCacheLoadedAtMs = 0L;

    private volatile Long cachedPlotId = null;
    private volatile int cachedBlockCapBreakerOwned = 0;
    private volatile int cachedPlotSizeBreakerOwned = 0;

    private volatile int cachedVoidItemCapStep = 0;
    private volatile int cachedDefaultItemCap = 1;
    private volatile int cachedItemCapStep = 0;
    private volatile int cachedMaxItemCap = Integer.MAX_VALUE;



    private boolean economyStateEquals(EconomyUiState a, EconomyUiState b) {
        if (a == b) return true;
        if (a == null || b == null) return false;

        return a.prestigeEnabled() == b.prestigeEnabled()
                && a.instantPrestigeEnabled() == b.instantPrestigeEnabled()
                && a.plotMinusEnabled() == b.plotMinusEnabled()
                && a.plotPlusEnabled() == b.plotPlusEnabled()
                && a.itemCapPlusEnabled() == b.itemCapPlusEnabled()
                && a.placedItems() == b.placedItems()
                && a.itemCap() == b.itemCap()
                && safeEq(a.moneyText(), b.moneyText())
                && safeEq(a.roleText(), b.roleText())
                && safeEq(a.voidText(), b.voidText())
                && safeEq(a.prestigeText(), b.prestigeText())
                && safeEq(a.plotIdText(), b.plotIdText())
                && safeEq(a.plotAreaText(), b.plotAreaText())
                && safeEq(a.prestigeTooltip(), b.prestigeTooltip());
    }

    private boolean safeEq(String x, String y) {
        return java.util.Objects.equals(x, y);
    }



    public MatterWorksGUI(GridManager gm,
                          BlockRegistry reg,
                          UUID initialUuid,
                          Runnable onSave,
                          MariaDBAdapter repo) {

        this.gridManager = gm;
        this.registry = reg;
        this.repository = repo;
        this.onSave = onSave;
        this.currentPlayerUuid = initialUuid;



        com.matterworks.core.ui.swing.debug.UiDebug.install();


        this.factoryPanel = new FactoryPanel(
                gridManager,
                registry,
                currentPlayerUuid,
                this::updateLabels,
                this::requestEconomyRefresh
        );

        this.rightTabbedPane.setPreferredSize(new Dimension(340, 0));

        this.glassPane = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(new Color(0, 0, 0, 180));
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(Color.WHITE);
                g.setFont(new Font("SansSerif", Font.BOLD, 26));
                String msg = "SWITCHING VIEW...";
                FontMetrics fm = g.getFontMetrics();
                g.drawString(msg, (getWidth() - fm.stringWidth(msg)) / 2, getHeight() / 2);
            }
        };
        glassPane.setOpaque(false);
        glassPane.addMouseListener(new java.awt.event.MouseAdapter() {});
        glassPane.addMouseMotionListener(new java.awt.event.MouseAdapter() {});
        setGlassPane(glassPane);

        // usa il costruttore a 10 parametri: classic + instant
        this.topBar = new TopBarPanel(
                this::selectTool,
                this::buyToolRightClick,
                this::changeLayer,
                this::selectStructureTool,
                this::handleSOS,
                this::handleSave,
                this::handleReset,
                this::handlePrestige,
                this::handleInstantPrestige,
                this::handleBooster,       // ✅ NEW
                this::handleDeletePlayer
        );



        // plot resize buttons (admin only; dominio fa i controlli)
        statusBar.setPlotResizeActions(
                () -> handlePlotResize(-1),
                () -> handlePlotResize(+1)
        );

        // NEW: item cap "+" button action (NO admin gate in UI)
        statusBar.setItemCapIncreaseAction(this::handleItemCapIncrease);

        refreshPlayerList(true);

        topBar.getPlayerSelector().addActionListener(e -> {
            if (suppressPlayerEvents) return;
            if (!glassPane.isVisible() && !isSwitching) handlePlayerSwitch();
        });

        setTitle("MatterWorks Architect - Multi-User Management");
        setSize(1480, 900);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

        updateTabs();

        add(topBar, BorderLayout.NORTH);
        add(factoryPanel, BorderLayout.CENTER);
        add(rightTabbedPane, BorderLayout.EAST);
        add(statusBar, BorderLayout.SOUTH);

        economyTimer = new Timer(600, e -> requestEconomyRefreshAsync());
        heartbeatTimer = new Timer(10_000, e -> {
            UUID u = currentPlayerUuid;
            if (u != null) gridManager.touchPlayer(u);
        });


        economyTimer.start();
        requestEconomyRefreshAsync();
        heartbeatTimer.start();

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { shutdownAndExit(); }
        });

        setVisible(true);
        factoryPanel.requestFocusInWindow();

        updateLabels();
        updateEconomyLabelsForce();

        if (currentPlayerUuid != null) {
            runOffEdt(() -> {
                safeOpenSession(currentPlayerUuid);
                gridManager.loadPlotFromDB(currentPlayerUuid);
                gridManager.touchPlayer(currentPlayerUuid);
                SwingUtilities.invokeLater(this::updateEconomyLabelsForce);
            });
        }
    }

    private void handleBooster() {
        UUID u = currentPlayerUuid;
        if (u == null) return;

        var boosters = gridManager.getActiveBoosters(u);

        if (boosters == null || boosters.isEmpty()) {
            JOptionPane.showMessageDialog(
                    this,
                    "No active boosters.",
                    "Boosters",
                    JOptionPane.INFORMATION_MESSAGE
            );
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (BoosterStatus b : boosters) {
            sb.append("- ")
                    .append(b.displayName())
                    .append(" | x")
                    .append(String.format(Locale.US, "%.2f", b.multiplier()))
                    .append(" | remaining: ")
                    .append(formatRemainingSeconds(b.remainingSeconds()))
                    .append("\n");
        }

        JOptionPane.showMessageDialog(
                this,
                sb.toString(),
                "Active Boosters",
                JOptionPane.INFORMATION_MESSAGE
        );
    }

    private void requestEconomyRefreshAsync() {
        // Snapshot the target player now to avoid races while switching players
        final UUID u = currentPlayerUuid;

        if (u == null) {
            // Apply "empty" state fast on EDT
            SwingUtilities.invokeLater(this::applyEmptyEconomyUi);
            return;
        }

        // Avoid piling up background tasks
        if (!economyUpdateInFlight.compareAndSet(false, true)) return;

        runOffEdt(() -> {
            try {
                // 1) Compute the UI state in background (this is already your pattern)
                EconomyUiState s = computeEconomyUiState(u);
                lastEconomyUiState = s;

                // 2) IMPORTANT: update cached DB placed count in BACKGROUND (never on EDT)
                // This is used only for tooltip/debug and for snapshot-empty fallback.
                try {
                    int dbPlaced = repository.getPlotItemsPlaced(u);
                    cachedDbPlacedItems = dbPlaced;
                    cachedDbPlacedItemsAtMs = System.currentTimeMillis();
                } catch (Throwable t) {
                    // Keep old cache if DB fails (do NOT crash refresh)
                    // Optional debug:
                    // t.printStackTrace();
                }

                // 3) Apply on EDT, but only if the user didn't switch player in the meantime
                SwingUtilities.invokeLater(() -> {
                    try {
                        if (currentPlayerUuid == null || !currentPlayerUuid.equals(u)) {
                            // Player changed while this task was running -> skip applying stale UI
                            return;
                        }

                        if (s == null) applyEmptyEconomyUi();
                        else applyEconomyUiState(s);

                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                });

            } finally {
                economyUpdateInFlight.set(false);
            }
        });
    }


    // =========================
// FULL METHOD: computeEconomyUiState(UUID)
// =========================
    private EconomyUiState computeEconomyUiState(UUID u) {

        PlayerProfile p = gridManager.getCachedProfile(u);
        if (p == null) return null;

        boolean techUnlocked = gridManager.getTechManager().isPrestigeUnlocked(p);
        boolean canPrestige = gridManager.canPerformPrestige(u);
        double cost = gridManager.getPrestigeActionCost(u);

        boolean prestigeEnabled = canPrestige;
        String prestigeTooltip;

        if (!techUnlocked) {
            prestigeTooltip = "Unlock the 'Prestige' tech node first.";
        } else if (p.isAdmin()) {
            prestigeTooltip = "Prestige unlocked (ADMIN: no fee).";
        } else {
            int fee = (int) Math.round(cost);
            if (!canPrestige) prestigeTooltip = "Prestige fee: $" + fee + " (not enough money).";
            else prestigeTooltip = "Prestige fee: $" + fee;
        }

        boolean instantEnabled = gridManager.canInstantPrestige(u);

        double money = p.getMoney();
        String rank = String.valueOf(p.getRank());
        boolean isAdmin = p.isAdmin();
        int voidCoins = p.getVoidCoins();
        int prestige = Math.max(0, p.getPrestigeLevel());

        Long pid = cachedPlotId;

        // This returns the placed item count, and may fallback to DB (debug already warns if it happens on EDT)
        int placed = computePlacedItemsIncludingStructures(u);

        int effectiveCap;
        try {
            effectiveCap = gridManager.getEffectiveItemPlacedOnPlotCap(u);
        } catch (Throwable t) {
            int baseCap = safeGetDefaultItemCap();
            int step = safeGetItemCapStep();
            int maxCap = safeGetMaxItemCap();
            effectiveCap = computeEffectiveCap(baseCap, step, maxCap, prestige);
        }

        int voidStep = cachedVoidItemCapStep;

        boolean itemCapPlusEnabled = (voidStep > 0) && (isAdmin || cachedBlockCapBreakerOwned > 0);
        boolean plotPlusEnabled = isAdmin || cachedPlotSizeBreakerOwned > 0;
        boolean plotMinusEnabled = isAdmin;

        String plotAreaStr = "---";
        try {
            GridManager.PlotAreaInfo info = gridManager.getPlotAreaInfo(u);
            if (info != null) {
                int hCap = 4;
                try {
                    hCap = gridManager.getPlotHeightCap(u);
                } catch (Throwable ignored) {}

                plotAreaStr = info.unlockedX() + "x" + info.unlockedY()
                        + " (+" + info.extraX() + "/+" + info.extraY() + ")"
                        + " MAX " + info.maxX() + "x" + info.maxY()
                        + " INC " + info.increaseX() + "x" + info.increaseY()
                        + " | H " + hCap;
            }
        } catch (Throwable ignored) {}

        String plotIdText = "PLOT ID: " + (pid == null ? "---" : pid);
        String plotAreaText = "AREA: " + plotAreaStr;

        return new EconomyUiState(
                prestigeEnabled,
                prestigeTooltip,
                instantEnabled,
                "MONEY: $" + (int) Math.round(money),
                "[" + rank + "]",
                "VOID: " + voidCoins,
                "PRESTIGE: " + prestige,
                plotIdText,

                // NEW: pass the numeric values directly (no more "ITEMS: x / y" strings)
                placed,
                effectiveCap,

                plotAreaText,
                plotMinusEnabled,
                plotPlusEnabled,
                itemCapPlusEnabled
        );
    }




    private void applyEmptyEconomyUi() {
        EconomyUiState prev = lastEconomyUiState;

        EconomyUiState next = new EconomyUiState(
                false,  // prestigeEnabled
                null,   // prestigeTooltip
                false,  // instantPrestigeEnabled
                "MONEY: $---",
                "[---]",
                "VOID: ---",
                "PRESTIGE: ---",
                "PLOT ID: ---",
                -1,     // placedItems unknown
                -1,     // itemCap unknown
                null,   // plotAreaText unknown
                false,  // plotMinusEnabled
                false,  // plotPlusEnabled
                false   // itemCapPlusEnabled
        );

        if (prev != null && economyStateEquals(prev, next)) return;

        // --- Top bar ---
        topBar.setPrestigeButtonEnabled(false);
        topBar.setPrestigeButtonToolTip(null);

        topBar.setInstantPrestigeButtonEnabled(false);
        topBar.setInstantPrestigeButtonToolTip(null);

        setLabelIfChanged(topBar.getMoneyLabel(), next.moneyText());
        setLabelIfChanged(topBar.getRoleLabel(), next.roleText());
        setLabelIfChanged(topBar.getVoidCoinsLabel(), next.voidText());
        setLabelIfChanged(topBar.getPrestigeLabel(), next.prestigeText());

        // --- Status bar ---
        statusBar.setPlotId(next.plotIdText());
        statusBar.setPlotItemsUnknown();
        statusBar.setPlotAreaUnknown();

        statusBar.setPlotMinusEnabled(false);
        statusBar.setPlotPlusEnabled(false);
        statusBar.setItemCapIncreaseEnabled(false);

        lastEconomyUiState = next;
    }





    private void applyEconomyUiState(EconomyUiState s) {
        if (s == null) {
            applyEmptyEconomyUi();
            return;
        }

        EconomyUiState prev = lastEconomyUiState;
        if (prev != null && economyStateEquals(prev, s)) return;

        // --- Top bar: prestige buttons ---
        if (prev == null || prev.prestigeEnabled() != s.prestigeEnabled()) {
            topBar.setPrestigeButtonEnabled(s.prestigeEnabled());
        }
        if (prev == null || !java.util.Objects.equals(prev.prestigeTooltip(), s.prestigeTooltip())) {
            topBar.setPrestigeButtonToolTip(s.prestigeTooltip());
        }
        if (prev == null || prev.instantPrestigeEnabled() != s.instantPrestigeEnabled()) {
            topBar.setInstantPrestigeButtonEnabled(s.instantPrestigeEnabled());
        }

        // --- Top bar labels ---
        setLabelIfChanged(topBar.getMoneyLabel(), s.moneyText());
        setLabelIfChanged(topBar.getRoleLabel(), s.roleText());
        setLabelIfChanged(topBar.getVoidCoinsLabel(), s.voidText());
        setLabelIfChanged(topBar.getPrestigeLabel(), s.prestigeText());

        // --- Status bar: plot id ---
        if (prev == null || !java.util.Objects.equals(prev.plotIdText(), s.plotIdText())) {
            statusBar.setPlotId(s.plotIdText());
        }

        // --- Status bar: items/cap (numeric, no parsing) ---
        if (s.placedItems() < 0 || s.itemCap() < 0) {
            if (prev == null || prev.placedItems() >= 0 || prev.itemCap() >= 0) {
                statusBar.setPlotItemsUnknown();
            }
        } else {
            if (prev == null || prev.placedItems() != s.placedItems() || prev.itemCap() != s.itemCap()) {
                statusBar.setPlotItems(s.placedItems(), s.itemCap());
            }
        }

        // --- Status bar: plot area ---
        if (s.plotAreaText() == null) {
            if (prev == null || prev.plotAreaText() != null) {
                statusBar.setPlotAreaUnknown();
            }
        } else {
            if (prev == null || !java.util.Objects.equals(prev.plotAreaText(), s.plotAreaText())) {
                statusBar.setPlotAreaText(s.plotAreaText());
            }
        }

        // --- Status bar: buttons enable ---
        if (prev == null || prev.plotMinusEnabled() != s.plotMinusEnabled()) {
            statusBar.setPlotMinusEnabled(s.plotMinusEnabled());
        }
        if (prev == null || prev.plotPlusEnabled() != s.plotPlusEnabled()) {
            statusBar.setPlotPlusEnabled(s.plotPlusEnabled());
        }
        if (prev == null || prev.itemCapPlusEnabled() != s.itemCapPlusEnabled()) {
            statusBar.setItemCapIncreaseEnabled(s.itemCapPlusEnabled());
        }

        lastEconomyUiState = s;
    }


    private String formatRemainingSeconds(long seconds) {
        if (seconds < 0) return "LIFETIME";
        long s = Math.max(0, seconds);
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        return String.format(Locale.US, "%02dh %02dm %02ds", h, m, sec);
    }


    // ===== TopBar actions =====

    private void selectTool(String itemId) {
        factoryPanel.setTool(itemId);
        factoryPanel.requestFocusInWindow();
        updateLabels();
        if (currentPlayerUuid != null) gridManager.touchPlayer(currentPlayerUuid);
    }

    private void ensureEconomyUiDebouncerInstalled() {
        // Keep the same method name to avoid refactor churn,
        // but switch implementation from "debounce one-shot" to "fixed-rate coalescing".
        if (economyUiTimer != null) return;

        // Target: 4–10Hz. 150ms ~= 6.6Hz
        final int TICK_MS = 150;

        // Runs on EDT
        economyUiTimer = new javax.swing.Timer(TICK_MS, e -> {
            // Coalescing: only act if something marked the UI as dirty
            if (!economyUiDirty) return;

            // If compute is already running, keep dirty=true and wait next tick
            if (economyComputeInFlight) return;

            // Consume the dirty flag (latest snapshot wins)
            economyUiDirty = false;
            economyUiLastApplyAtMs = System.currentTimeMillis();

            long start = System.nanoTime();
            requestEconomyUiRefreshDebounced();
            long ms = (System.nanoTime() - start) / 1_000_000L;

            if (ms >= 40) {
                System.out.println("[UI-DBG] SLOW " + ms + "ms :: EDT economy tick (coalesced)");
            }
        });
        economyUiTimer.setRepeats(true);
    }




    private void buyToolRightClick(String itemId, Integer amount) {
        UUID u = currentPlayerUuid;
        if (u == null) return;

        com.matterworks.core.ui.swing.debug.UiDebug.logThread("buyToolRightClick(" + itemId + ", amount=" + amount + ")");

        runOffEdt(() -> {
            com.matterworks.core.ui.swing.debug.UiDebug.time("BG buyToolRightClick body", () -> {
                gridManager.touchPlayer(u);

                int qty = (amount != null ? amount : 1);

                boolean ok = com.matterworks.core.ui.swing.debug.UiDebug.time(
                        "CORE gridManager.buyItem(" + itemId + ", qty=" + qty + ")",
                        () -> gridManager.buyItem(u, itemId, qty),
                        10
                );

                com.matterworks.core.ui.swing.debug.UiDebug.log("buyItem result ok=" + ok);

                if (ok) {
                    SwingUtilities.invokeLater(() -> {
                        com.matterworks.core.ui.swing.debug.UiDebug.time("EDT after-buy requestEconomyRefresh()", this::requestEconomyRefresh, 5);
                    });
                }
            }, 1);
        });
    }



    private void selectStructureTool(String nativeId) {
        factoryPanel.setTool("STRUCTURE:" + nativeId);
        factoryPanel.requestFocusInWindow();
        updateLabels();
    }

    private void changeLayer(Integer delta) {
        int d = (delta != null ? delta : 0);

        int desired = factoryPanel.getCurrentLayer() + d;
        factoryPanel.setLayer(desired); // FactoryPanel will clamp based on current height cap

        int actual = factoryPanel.getCurrentLayer();
        topBar.setLayerValue(actual);

        factoryPanel.requestFocusInWindow();
        updateLabels();
    }


    private void handleSOS() {
        UUID u = currentPlayerUuid;
        if (u == null) return;

        setLoading(true);

        runOffEdt(() -> {
            boolean ok = false;
            try {
                gridManager.touchPlayer(u);
                ok = gridManager.attemptBailout(u);
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                final boolean fOk = ok;
                SwingUtilities.invokeLater(() -> {
                    setLoading(false);
                    if (fOk) JOptionPane.showMessageDialog(this, "SOS approved! Funds granted.");
                    requestEconomyRefresh();
                });
            }
        });
    }


    private void handleSave() {
        UUID u = currentPlayerUuid;
        if (u == null) return;

        setLoading(true);

        runOffEdt(() -> {
            try {
                gridManager.touchPlayer(u);
                onSave.run();
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                SwingUtilities.invokeLater(() -> {
                    setLoading(false);
                    requestEconomyRefresh();
                });
            }
        });
    }


    private void handleReset() {
        UUID u = currentPlayerUuid;
        if (u == null) return;

        int res = JOptionPane.showConfirmDialog(
                this,
                "Reset plot? All progress will be lost.",
                "Reset",
                JOptionPane.YES_NO_OPTION
        );
        if (res != JOptionPane.YES_OPTION) return;

        setLoading(true);

        runOffEdt(() -> {
            try {
                gridManager.touchPlayer(u);
                gridManager.resetUserPlot(u);
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                SwingUtilities.invokeLater(() -> {
                    setLoading(false);
                    factoryPanel.forceRefreshNow();
                    requestEconomyRefresh();
                });
            }
        });
    }



    private void handlePrestige() {
        UUID u = currentPlayerUuid;
        if (u == null) return;

        PlayerProfile p = gridManager.getCachedProfile(u);
        if (p == null) return;

        // NEW: gate is CORE-driven (tech unlock + money check, admin excluded from money)
        boolean canPrestige = gridManager.canPerformPrestige(u);
        boolean techUnlocked = gridManager.getTechManager().isPrestigeUnlocked(p);

        if (!techUnlocked) {
            JOptionPane.showMessageDialog(this,
                    "Prestige is locked.\nUnlock the 'Prestige' tech node first.",
                    "Prestige",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        double cost = gridManager.getPrestigeActionCost(u);

        if (!canPrestige) {
            // Tech unlocked but not enough money (player only)
            JOptionPane.showMessageDialog(this,
                    "Not enough money to Prestige.\nRequired: $" + (int) Math.round(cost),
                    "Prestige",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        ServerConfig cfg = repository.loadServerConfig();
        int addVoid = Math.max(0, cfg.prestigeVoidCoinsAdd());
        int plotBonus = Math.max(0, cfg.prestigePlotBonus());

        int currentPrestige = Math.max(0, p.getPrestigeLevel());
        int nextPrestige = currentPrestige + 1;

        int baseCap = safeGetDefaultItemCap();
        int step = safeGetItemCapStep();
        int maxCap = safeGetMaxItemCap();

        int capNow = computeEffectiveCap(baseCap, step, maxCap, currentPrestige);
        int capNext = computeEffectiveCap(baseCap, step, maxCap, nextPrestige);

        String feeLine = p.isAdmin()
                ? " - Prestige fee: $0 (ADMIN)\n"
                : " - Prestige fee: $" + (int) Math.round(cost) + "\n";

        String msg =
                "Advance to PRESTIGE " + nextPrestige + "?\n\n" +
                        "This will:\n" +
                        feeLine +
                        " - Reset plot, inventory and tech tree\n" +
                        " - Grant +" + addVoid + " VOID coins\n" +
                        " - Increase plot size by +" + plotBonus + " X and +" + plotBonus + " Y\n" +
                        " - Increase item cap: " + capNow + " -> " + capNext + "\n\n" +
                        "This action cannot be undone.";

        int res = JOptionPane.showConfirmDialog(
                this,
                msg,
                "Prestige",
                JOptionPane.YES_NO_OPTION
        );
        if (res != JOptionPane.YES_OPTION) return;

        topBar.setPrestigeButtonEnabled(false);

        gridManager.touchPlayer(u);
        gridManager.prestigeUser(u);

        factoryPanel.forceRefreshNow();
        updateEconomyLabelsForce();
    }


    // NEW: Instant Prestige (premium, NO reset)
    private void handleInstantPrestige() {
        UUID u = currentPlayerUuid;
        if (u == null) return;

        PlayerProfile p = gridManager.getCachedProfile(u);
        if (p == null) return;

        if (!gridManager.canInstantPrestige(u)) {
            JOptionPane.showMessageDialog(
                    this,
                    "Instant Prestige locked.\nBuy 'instant_prestige' in the Void Shop first.",
                    "Instant Prestige",
                    JOptionPane.WARNING_MESSAGE
            );
            return;
        }

        ServerConfig cfg = repository.loadServerConfig();
        int addVoid = Math.max(0, cfg.prestigeVoidCoinsAdd());
        int plotBonus = Math.max(0, cfg.prestigePlotBonus());

        int currentPrestige = Math.max(0, p.getPrestigeLevel());
        int nextPrestige = currentPrestige + 1;

        int baseCap = safeGetDefaultItemCap();
        int step = safeGetItemCapStep();
        int maxCap = safeGetMaxItemCap();

        int capNow = computeEffectiveCap(baseCap, step, maxCap, currentPrestige);
        int capNext = computeEffectiveCap(baseCap, step, maxCap, nextPrestige);

        String msg =
                "Use INSTANT PRESTIGE to reach PRESTIGE " + nextPrestige + "?\n\n" +
                        "This will (NO RESET):\n" +
                        " - Keep plot and tech tree (no malus)\n" +
                        " - +1 Prestige level\n" +
                        " - Grant +" + addVoid + " VOID coins\n" +
                        " - Increase plot size by +" + plotBonus + " X and +" + plotBonus + " Y\n" +
                        " - Increase item cap: " + capNow + " -> " + capNext + "\n\n" +
                        (p.isAdmin() ? "(ADMIN: does not consume item)\n" : "Consumes: 1x instant_prestige\n");

        int res = JOptionPane.showConfirmDialog(
                this,
                msg,
                "Instant Prestige",
                JOptionPane.YES_NO_OPTION
        );
        if (res != JOptionPane.YES_OPTION) return;

        gridManager.touchPlayer(u);
        gridManager.instantPrestigeUser(u);

        factoryPanel.forceRefreshNow();
        updateEconomyLabelsForce();
    }

    private void handlePlotResize(int dir) {
        UUID u = currentPlayerUuid;
        if (u == null) return;

        PlayerProfile p = gridManager.getCachedProfile(u);
        if (p == null) return;

        boolean isAdmin = p.isAdmin();

        // '-' admin only
        if (dir < 0 && !isAdmin) return;

        setLoading(true);

        runOffEdt(() -> {
            boolean ok = false;
            try {
                if (dir > 0) ok = gridManager.increasePlotUnlockedArea(u);
                else ok = gridManager.decreasePlotUnlockedArea(u);
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                final boolean fOk = ok;

                SwingUtilities.invokeLater(() -> {
                    setLoading(false);

                    if (!fOk) {
                        String msg = (dir > 0)
                                ? "Impossibile espandere: già al MAX o non consentito."
                                : "Impossibile ridurre: ci sono macchine fuori dalla nuova area o sei già allo START.";
                        JOptionPane.showMessageDialog(this, msg, "Plot Resize", JOptionPane.WARNING_MESSAGE);
                        return;
                    }

                    factoryPanel.forceRefreshNow();
                    requestEconomyRefresh();
                });
            }
        });
    }



    // NEW: Item cap "+" (NO admin gate). Core decide se applicare o meno.
    private void handleItemCapIncrease() {
        UUID u = currentPlayerUuid;
        if (u == null) return;

        runOffEdt(() -> {
            gridManager.touchPlayer(u);

            final int step = safeGetVoidItemCapStep();
            final int beforeCap = gridManager.getEffectiveItemPlacedOnPlotCap(u);

            boolean okTmp;
            try {
                okTmp = gridManager.increaseMaxItemPlacedOnPlotCap(u);
            } catch (Throwable t) {
                t.printStackTrace();
                okTmp = false;
            }

            final boolean ok = okTmp;
            final int afterCap = gridManager.getEffectiveItemPlacedOnPlotCap(u);
            final int maxCap = safeGetMaxItemCap();

            SwingUtilities.invokeLater(() -> {
                // Refresh UI always
                updateEconomyLabelsForce();

                // Always show debug info (for now)
                JOptionPane.showMessageDialog(
                        this,
                        "ItemCap '+' pressed\n\n" +
                                "voidStep (DB) = " + step + "\n" +
                                "max_item_placed_on_plot (DB) = " + (maxCap == Integer.MAX_VALUE ? "UNLIMITED(0)" : maxCap) + "\n" +
                                "cap before = " + beforeCap + "\n" +
                                "cap after  = " + afterCap + "\n" +
                                "ok = " + ok + "\n\n" +
                                "If step is 0 => add the column + set a value in DB.\n" +
                                "If max is low => you are clamped by max.",
                        "Item Cap Debug",
                        ok ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE
                );
            });
        });
    }


    private void requestEconomyRefresh() {
        economyUiLastRequestAtMs = System.currentTimeMillis();
        economyUiDirty = true;

        // Install + start fixed-rate coalescing loop (4–10Hz-ish)
        SwingUtilities.invokeLater(() -> {
            ensureEconomyUiDebouncerInstalled();

            // Do not spam restart: fixed-rate timer will pick "latest wins"
            if (economyUiTimer != null && !economyUiTimer.isRunning()) {
                economyUiTimer.start();
            }
        });
    }




    // ===== Tabs =====

    private void updateTabs() {
        for (Component c : rightTabbedPane.getComponents()) {
            if (c instanceof InventoryDebugPanel p) p.dispose();
            if (c instanceof TechTreePanel p) p.dispose();
            if (c instanceof VoidShopPanel p) p.dispose();
            if (c instanceof ProductionPanel p) p.dispose();
            if (c instanceof FactionsPanel p) p.dispose(); // ✅ NEW
        }

        rightTabbedPane.removeAll();

        if (currentPlayerUuid != null) {
            rightTabbedPane.addTab("Shop", new InventoryDebugPanel(repository, currentPlayerUuid, gridManager, this::requestEconomyRefresh));
            rightTabbedPane.addTab("Tech Tree", new TechTreePanel(repository, currentPlayerUuid, gridManager));
            rightTabbedPane.addTab("Void Shop", new VoidShopPanel(repository, currentPlayerUuid, gridManager, this::requestEconomyRefresh));
            rightTabbedPane.addTab("Production", new ProductionPanel(currentPlayerUuid, gridManager));

            // ✅ NEW: Factions (server-wide, DB-driven)
            rightTabbedPane.addTab("Factions", new FactionsPanel(repository, gridManager));


        } else {
            rightTabbedPane.addTab("Info", new JPanel() {{ add(new JLabel("No Player Selected")); }});
        }
    }



    // ===== Switching / Session =====

    private void handlePlayerSwitch() {
        if (suppressPlayerEvents) return;

        Object sel = topBar.getPlayerSelector().getSelectedItem();

        com.matterworks.core.ui.swing.debug.UiDebug.logThread(
                "handlePlayerSwitch() selected=" + (sel != null ? sel.getClass().getName() : "null")
        );

        // Special item: add new player
        if ("--- ADD NEW PLAYER ---".equals(sel)) {
            String n = JOptionPane.showInputDialog("Name:");
            if (n != null && !n.isBlank()) {
                com.matterworks.core.ui.swing.debug.UiDebug.log("createNewPlayer name=" + n);
                gridManager.createNewPlayer(n);
                refreshPlayerList(true);
            } else {
                refreshPlayerList(false);
            }
            return;
        }

        if (!(sel instanceof PlayerProfile p)) return;

        UUID newUuid = p.getPlayerId();
        if (newUuid == null) return;
        if (newUuid.equals(this.currentPlayerUuid)) return;

        final UUID oldUuid = this.currentPlayerUuid;

        isSwitching = true;
        setLoading(true);

        // Stop timers during switching to avoid EDT bursts while tabs/layout are changing
        SwingUtilities.invokeLater(() -> {
            try { economyTimer.stop(); } catch (Exception ignored) {}
            try { heartbeatTimer.stop(); } catch (Exception ignored) {}
        });

        runOffEdt(() -> {
            com.matterworks.core.ui.swing.debug.UiDebug.logThread("SWITCH BG start old=" + oldUuid + " new=" + newUuid);

            try {
                com.matterworks.core.ui.swing.debug.UiDebug.time("BG onSave()", () -> {
                    try {
                        if (oldUuid != null) gridManager.touchPlayer(oldUuid);
                        onSave.run();
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }, 10);

                com.matterworks.core.ui.swing.debug.UiDebug.time("BG safeCloseSession(old)", () -> safeCloseSession(oldUuid), 10);
                com.matterworks.core.ui.swing.debug.UiDebug.time("BG safeOpenSession(new)", () -> safeOpenSession(newUuid), 10);

                com.matterworks.core.ui.swing.debug.UiDebug.time("BG gridManager.loadPlotFromDB(new)", () -> {
                    gridManager.loadPlotFromDB(newUuid);
                }, 10);

                com.matterworks.core.ui.swing.debug.UiDebug.time("BG gridManager.touchPlayer(new)", () -> {
                    gridManager.touchPlayer(newUuid);
                }, 10);

                SwingUtilities.invokeLater(() -> {
                    com.matterworks.core.ui.swing.debug.UiDebug.time("EDT switch apply UI", () -> {
                        currentPlayerUuid = newUuid;

                        factoryPanel.setPlayerUuid(newUuid);

                        // IMPORTANT: reset caches + invalidate last snapshot
                        resetEconomyCache();
                        lastEconomyUiState = null;

                        // Rebuild tabs (still on EDT), but avoid also doing economy compute/apply synchronously here
                        updateTabs();
                        updateLabels();

                        // Instead of updateEconomyLabelsForce() (which triggers apply burst here),
                        // request the snapshot refresh (compute off-EDT + apply small diff on EDT).
                        requestEconomyRefreshAsync();

                        setLoading(false);
                        isSwitching = false;

                        // Restart timers after switch is complete
                        try { economyTimer.start(); } catch (Exception ignored) {}
                        try { heartbeatTimer.start(); } catch (Exception ignored) {}

                        factoryPanel.requestFocusInWindow();
                    }, 5);
                });

            } catch (Exception ex) {
                ex.printStackTrace();
                SwingUtilities.invokeLater(() -> {
                    setLoading(false);
                    isSwitching = false;
                    try { economyTimer.start(); } catch (Exception ignored) {}
                    try { heartbeatTimer.start(); } catch (Exception ignored) {}
                });
            } finally {
                com.matterworks.core.ui.swing.debug.UiDebug.logThread("SWITCH BG end old=" + oldUuid + " new=" + newUuid);
            }
        });
    }



    private void handleDeletePlayer() {
        if (currentPlayerUuid == null) return;

        int confirm = JOptionPane.showConfirmDialog(
                this,
                "PERMANENTLY DELETE THIS PLAYER?",
                "Delete Player",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.ERROR_MESSAGE
        );
        if (confirm != JOptionPane.YES_OPTION) return;

        UUID toDelete = currentPlayerUuid;

        isSwitching = true;
        setLoading(true);

        runOffEdt(() -> {
            try {
                try { onSave.run(); } catch (Exception ex) { ex.printStackTrace(); }

                safeCloseSession(toDelete);
                gridManager.deletePlayer(toDelete);

                SwingUtilities.invokeLater(() -> {
                    refreshPlayerList(true);

                    if (!cachedPlayerList.isEmpty()) {
                        PlayerProfile next = cachedPlayerList.get(0);
                        UUID nextId = next.getPlayerId();

                        currentPlayerUuid = nextId;
                        factoryPanel.setPlayerUuid(nextId);

                        runOffEdt(() -> {
                            safeOpenSession(nextId);
                            gridManager.loadPlotFromDB(nextId);
                            gridManager.touchPlayer(nextId);

                            SwingUtilities.invokeLater(() -> {
                                resetEconomyCache();
                                updateTabs();
                                updateLabels();
                                updateEconomyLabelsForce();
                                setLoading(false);
                                isSwitching = false;
                                factoryPanel.requestFocusInWindow();
                            });
                        });

                    } else {
                        currentPlayerUuid = null;
                        factoryPanel.setPlayerUuid(null);
                        resetEconomyCache();
                        updateTabs();
                        updateLabels();
                        updateEconomyLabelsForce();
                        setLoading(false);
                        isSwitching = false;
                    }
                });

            } catch (Exception ex) {
                ex.printStackTrace();
                SwingUtilities.invokeLater(() -> {
                    setLoading(false);
                    isSwitching = false;
                });
            }
        });
    }

    private void safeOpenSession(UUID uuid) { if (uuid != null) repository.openPlayerSession(uuid); }
    private void safeCloseSession(UUID uuid) { if (uuid != null) repository.closePlayerSession(uuid); }

    private void shutdownAndExit() {
        if (isSwitching) return;

        isSwitching = true;
        setLoading(true);

        runOffEdt(() -> {
            try {
                try { onSave.run(); } catch (Exception ex) { ex.printStackTrace(); }
                try { safeCloseSession(currentPlayerUuid); } catch (Exception ex) { ex.printStackTrace(); }

                SwingUtilities.invokeLater(() -> {
                    try { economyTimer.stop(); } catch (Exception ignored) {}
                    try { heartbeatTimer.stop(); } catch (Exception ignored) {}
                    try { factoryPanel.dispose(); } catch (Exception ignored) {}
                    try { dispose(); } catch (Exception ignored) {}
                    try { worker.shutdownNow(); } catch (Exception ignored) {}
                    System.exit(0);
                });

            } catch (Exception ex) {
                ex.printStackTrace();
                SwingUtilities.invokeLater(() -> {
                    try { factoryPanel.dispose(); } catch (Exception ignored) {}
                    try { dispose(); } catch (Exception ignored) {}
                    try { worker.shutdownNow(); } catch (Exception ignored) {}
                    System.exit(0);
                });
            }
        });
    }

    // ===== Labels / List =====

    private void refreshPlayerList(boolean forceDb) {
        JComboBox<Object> playerSelector = topBar.getPlayerSelector();
        if (playerSelector == null) return;

        suppressPlayerEvents = true;
        try {
            if (forceDb) {
                List<PlayerProfile> fromDb = repository.getAllPlayers();
                cachedPlayerList = (fromDb != null) ? fromDb : new ArrayList<>();
            }

            // Build desired model snapshot
            List<Object> desired = new ArrayList<>();
            for (PlayerProfile pp : cachedPlayerList) desired.add(pp);
            desired.add("--- ADD NEW PLAYER ---");

            // Compare current combo content with desired; if equal, do nothing (no removeAllItems/revalidate storm)
            boolean same = (playerSelector.getItemCount() == desired.size());
            if (same) {
                for (int i = 0; i < desired.size(); i++) {
                    Object cur = playerSelector.getItemAt(i);
                    Object want = desired.get(i);

                    if (cur instanceof PlayerProfile a && want instanceof PlayerProfile b) {
                        UUID au = a.getPlayerId();
                        UUID bu = b.getPlayerId();
                        if (au == null || bu == null || !au.equals(bu)) { same = false; break; }
                    } else {
                        if (!Objects.equals(cur, want)) { same = false; break; }
                    }
                }
            }

            if (!same) {
                playerSelector.removeAllItems();
                for (Object it : desired) playerSelector.addItem(it);
            }

            // Ensure selection is correct (without triggering unnecessary changes)
            if (currentPlayerUuid != null) {
                int desiredIndex = -1;
                for (int i = 0; i < playerSelector.getItemCount(); i++) {
                    Object it = playerSelector.getItemAt(i);
                    if (it instanceof PlayerProfile pp && currentPlayerUuid.equals(pp.getPlayerId())) {
                        desiredIndex = i;
                        break;
                    }
                }

                int curIndex = playerSelector.getSelectedIndex();
                if (desiredIndex >= 0 && curIndex != desiredIndex) {
                    playerSelector.setSelectedIndex(desiredIndex);
                }
            }

        } finally {
            suppressPlayerEvents = false;
        }
    }


    private void resetEconomyCache() {
        lastMoneyShown = Double.NaN;
        lastRankShown = null;
        lastPlotIdShown = null;
        lastAdminShown = false;
        lastVoidCoinsShown = Integer.MIN_VALUE;
        lastPrestigeShown = Integer.MIN_VALUE;

        lastPlotItemsShown = Integer.MIN_VALUE;
        lastPlotCapShown = Integer.MIN_VALUE;

        lastPlotAreaShown = null;
        lastPlotPlusEnabled = false;
        lastPlotMinusEnabled = false;


        lastItemCapPlusEnabled = false;
    }

    // ✅ COMPLETAMENTE RISCRITTO: compute off-EDT + apply on-EDT, senza azzerare cache a caso.
    private void updateEconomyLabelsForce() {
        final UUID u = currentPlayerUuid;

        if (u == null) {
            SwingUtilities.invokeLater(() -> {
                applyEmptyEconomyUi();
                statusBar.setToolTipText(null);
            });
            return;
        }

        // Compute off-EDT
        runOffEdt(() -> {
            final long t0 = System.nanoTime();
            EconomyUiState s = null;
            Throwable err = null;

            try {
                s = computeEconomyUiState(u);
            } catch (Throwable t) {
                err = t;
            }

            final long t1 = System.nanoTime();
            final EconomyUiState sFinal = s;
            final Throwable eFinal = err;

            // Apply on EDT
            SwingUtilities.invokeLater(() -> {
                final long a0 = System.nanoTime();

                try {
                    // If player changed while computing, do not apply stale UI
                    if (currentPlayerUuid == null || !currentPlayerUuid.equals(u)) return;

                    if (eFinal != null || sFinal == null) {
                        applyEmptyEconomyUi();
                    } else {
                        // ✅ USE THE REAL METHOD YOU HAVE
                        applyEconomyUiState(sFinal);
                    }

                } catch (Throwable t) {
                    t.printStackTrace();
                }

                final long a1 = System.nanoTime();
                long computeMs = (t1 - t0) / 1_000_000L;
                long applyMs = (a1 - a0) / 1_000_000L;

                if (applyMs > 40) {
                    System.out.println("[UI-DBG] SLOW updateEconomyLabelsForce(): compute="
                            + computeMs + "ms apply=" + applyMs + "ms");
                }
            });
        });
    }


    /**
     * ✅ Apply ultra-leggero:
     * - niente parsing stringhe "ITEMS: x / y"
     * - niente setText se non cambia
     * - niente revalidate/repaint manuali
     *
     * Nota: per togliere il parsing, EconomyUiState deve avere placed/cap come int
     * (vedi sotto).
     */


    private static void setLabelIfChanged(JLabel label, String text) {
        String cur = label.getText();
        if (cur == null || !cur.equals(text)) {
            label.setText(text);
        }
    }



    private void requestEconomyUiRefreshDebounced() {
        final UUID u = currentPlayerUuid;
        if (u == null) return;

        // If a compute is already in flight, do nothing:
        // the timer will tick again, and we also re-mark dirty when needed.
        if (economyComputeInFlight) return;
        economyComputeInFlight = true;

        runOffEdt(() -> {
            long t0 = System.nanoTime();
            EconomyUiState computed = null;
            Throwable err = null;

            try {
                computed = computeEconomyUiState(u); // OK: off-EDT
            } catch (Throwable t) {
                err = t;
            }

            long t1 = System.nanoTime();
            final EconomyUiState sFinal = computed;
            final Throwable eFinal = err;

            SwingUtilities.invokeLater(() -> {
                long a0 = System.nanoTime();
                try {
                    // If player changed while computing, do not apply stale UI.
                    if (currentPlayerUuid == null || !currentPlayerUuid.equals(u)) return;

                    if (eFinal != null || sFinal == null) {
                        applyEmptyEconomyUi();
                        lastAppliedEconomyState = null;
                    } else {
                        // Diff-based: apply only if snapshot changed
                        if (!economyStateEquals(lastAppliedEconomyState, sFinal)) {
                            applyEconomyUiState(sFinal);
                            lastAppliedEconomyState = sFinal;
                        }
                    }

                } finally {
                    economyComputeInFlight = false;

                    // If new changes happened while we were computing/applying,
                    // keep the loop alive: next tick will pick the latest state.
                    // (We intentionally do NOT start another compute immediately here.)
                    // economyUiDirty might already be true; no harm.
                }

                long a1 = System.nanoTime();
                long computeMs = (t1 - t0) / 1_000_000L;
                long applyMs = (a1 - a0) / 1_000_000L;

                if (applyMs > 40 || computeMs > 40) {
                    System.out.println("[UI-DBG] economy refresh: compute=" + computeMs + "ms apply=" + applyMs + "ms");
                }
            });
        });
    }




    private int computePlacedItemsIncludingStructures(UUID ownerId) {
        // Fast path: runtime snapshot (includes structures)
        try {
            var snap = gridManager.getSnapshot(ownerId);
            if (snap != null && !snap.isEmpty()) {
                IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
                for (PlacedMachine m : snap.values()) {
                    if (m == null) continue;
                    seen.put(m, Boolean.TRUE);
                }
                return seen.size();
            }
        } catch (Throwable ignored) {}

        // Snapshot empty: NEVER hit DB on EDT.
        if (SwingUtilities.isEventDispatchThread()) {
            Integer db = cachedDbPlacedItems;
            return (db != null ? db : 0);
        }

        // Background fallback allowed (rare)
        try {
            int db = repository.getPlotItemsPlaced(ownerId);
            cachedDbPlacedItems = db;
            cachedDbPlacedItemsAtMs = System.currentTimeMillis();
            return db;
        } catch (Throwable ignored) {
            return 0;
        }
    }




    private void updateLabels() {
        String t = factoryPanel.getCurrentToolName();
        if (t != null && t.startsWith("STRUCTURE:")) t = "STRUCT (" + t.substring(10) + ")";

        statusBar.setTool(t);
        statusBar.setDir(factoryPanel.getCurrentOrientationName());
        statusBar.setLayer(factoryPanel.getCurrentLayer());
        topBar.setLayerValue(factoryPanel.getCurrentLayer());
    }

    private void setLoading(boolean loading) {
        SwingUtilities.invokeLater(() -> {
            glassPane.setVisible(loading);
            topBar.getPlayerSelector().setEnabled(!loading);
        });
    }

    private void runOffEdt(Runnable r) {
        worker.submit(() -> {
            try { r.run(); } catch (Throwable t) { t.printStackTrace(); }
        });
    }

    // ===== Effective cap helpers (prestige-based) =====

    private int safeGetDefaultItemCap() {
        int base = 0;
        try { base = repository.getDefaultItemPlacedOnPlotCap(); }
        catch (Throwable ignored) {}
        return Math.max(1, base);
    }

    private int safeGetItemCapStep() {
        int step = invokeRepoInt("getItemCapIncreaseStep", 0);
        return Math.max(0, step);
    }

    // NEW: reads servergamestate.void_itemcap_increase_step if repository exposes it
    private int safeGetVoidItemCapStep() {
        int step = invokeRepoInt("getVoidItemCapIncreaseStep", 0);
        return Math.max(0, step);
    }

    private int safeGetMaxItemCap() {
        int max = invokeRepoInt("getMaxItemPlacedOnPlotCap", Integer.MAX_VALUE);
        if (max <= 0) return Integer.MAX_VALUE;
        return max;
    }

    private int computeEffectiveCap(int baseCap, int step, int maxCap, int prestigeLevel) {
        int base = Math.max(1, baseCap);
        int st = Math.max(0, step);
        int pr = Math.max(0, prestigeLevel);
        int mx = (maxCap <= 0 ? Integer.MAX_VALUE : maxCap);

        long raw = (long) base + (long) pr * (long) st;
        long clamped = Math.min(raw, (long) mx);
        if (clamped > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) Math.max(1L, clamped);
    }

    private int invokeRepoInt(String methodName, int fallback) {
        try {
            Method m = repository.getClass().getMethod(methodName);
            Object v = m.invoke(repository);
            if (v instanceof Integer i) return i;
        } catch (Throwable ignored) {}
        return fallback;
    }
}
