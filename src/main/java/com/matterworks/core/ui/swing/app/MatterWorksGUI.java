package com.matterworks.core.ui.swing.app;

import com.matterworks.core.common.GridPosition;
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

    private record EconomyUiState(
            boolean prestigeEnabled,
            String prestigeTooltip,
            boolean instantPrestigeEnabled,
            String moneyText,
            String roleText,
            String voidText,
            String prestigeText,
            String plotIdText,
            String plotItemsText,
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
        if (currentPlayerUuid == null) {
            // Apply "empty" state fast on EDT
            SwingUtilities.invokeLater(this::applyEmptyEconomyUi);
            return;
        }

        // Avoid piling up background tasks
        if (!economyUpdateInFlight.compareAndSet(false, true)) return;

        runOffEdt(() -> {
            try {
                EconomyUiState s = computeEconomyUiState(currentPlayerUuid);
                lastEconomyUiState = s;

                SwingUtilities.invokeLater(() -> {
                    try {
                        applyEconomyUiState(s);
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                });

            } finally {
                economyUpdateInFlight.set(false);
            }
        });
    }

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
                plotAreaStr = info.unlockedX() + "x" + info.unlockedY()
                        + " (+" + info.extraX() + "/+" + info.extraY() + ")"
                        + " MAX " + info.maxX() + "x" + info.maxY()
                        + " INC " + info.increaseX() + "x" + info.increaseY();
            }
        } catch (Throwable ignored) {}

        String plotIdText = "PLOT ID: " + (pid == null ? "---" : pid);
        String plotItemsText = "ITEMS: " + placed + " / " + effectiveCap;
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
                plotItemsText,
                plotAreaText,
                plotMinusEnabled,
                plotPlusEnabled,
                itemCapPlusEnabled
        );
    }


    private void applyEmptyEconomyUi() {
        topBar.setPrestigeButtonEnabled(false);
        topBar.setInstantPrestigeButtonEnabled(false);

        topBar.getMoneyLabel().setText("MONEY: $---");
        topBar.getRoleLabel().setText("[---]");
        topBar.getVoidCoinsLabel().setText("VOID: ---");
        topBar.getPrestigeLabel().setText("PRESTIGE: ---");

        statusBar.setPlotId("PLOT ID: ---");
        statusBar.setPlotItemsUnknown();
        statusBar.setPlotAreaUnknown();
        statusBar.setPlotMinusEnabled(false);
        statusBar.setPlotPlusEnabled(false);
        statusBar.setItemCapIncreaseEnabled(false);
    }

    private void applyEconomyUiState(EconomyUiState s) {
        if (s == null) return;

        topBar.setPrestigeButtonEnabled(s.prestigeEnabled());
        topBar.setPrestigeButtonToolTip(s.prestigeTooltip());
        topBar.setInstantPrestigeButtonEnabled(s.instantPrestigeEnabled());

        topBar.getMoneyLabel().setText(s.moneyText());
        topBar.getRoleLabel().setText(s.roleText());
        topBar.getVoidCoinsLabel().setText(s.voidText());
        topBar.getPrestigeLabel().setText(s.prestigeText());

        statusBar.setPlotId(s.plotIdText());

        // StatusBarPanel API:
        // - setPlotItems(int placed, int cap)
        int placed = 0;
        int cap = 0;
        try {
            // computeEconomyUiState creates: "ITEMS: <placed> / <cap>"
            String t = s.plotItemsText();
            int idxColon = t.indexOf(':');
            int idxSlash = t.indexOf('/');
            if (idxColon >= 0 && idxSlash >= 0) {
                String left = t.substring(idxColon + 1, idxSlash).trim();
                String right = t.substring(idxSlash + 1).trim();
                placed = Integer.parseInt(left);
                cap = Integer.parseInt(right);
            }
        } catch (Throwable ignored) {}

        statusBar.setPlotItems(placed, cap);

        // StatusBarPanel API:
        // - setPlotAreaText(String)
        statusBar.setPlotAreaText(s.plotAreaText());

        statusBar.setPlotMinusEnabled(s.plotMinusEnabled());
        statusBar.setPlotPlusEnabled(s.plotPlusEnabled());
        statusBar.setItemCapIncreaseEnabled(s.itemCapPlusEnabled());
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

    private void buyToolRightClick(String itemId, Integer amount) {
        UUID u = currentPlayerUuid;
        if (u == null) return;

        runOffEdt(() -> {
            gridManager.touchPlayer(u);
            boolean ok = gridManager.buyItem(u, itemId, amount != null ? amount : 1);
            if (ok) SwingUtilities.invokeLater(this::updateEconomyLabelsForce);
        });
    }

    private void selectStructureTool(String nativeId) {
        factoryPanel.setTool("STRUCTURE:" + nativeId);
        factoryPanel.requestFocusInWindow();
        updateLabels();
    }

    private void changeLayer(Integer delta) {
        int d = (delta != null ? delta : 0);
        int newY = Math.max(0, factoryPanel.getCurrentLayer() + d);
        factoryPanel.setLayer(newY);
        topBar.setLayerValue(newY);
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
        UUID u = currentPlayerUuid;

        // If no player selected, just paint the empty state on EDT.
        if (u == null) {
            SwingUtilities.invokeLater(this::updateEconomyLabelsForce);
            return;
        }

        // Avoid parallel fetches.
        if (!economyFetchRunning.compareAndSet(false, true)) {
            return;
        }

        runOffEdt(() -> {
            try {
                PlayerProfile p = gridManager.getCachedProfile(u);
                if (p == null) return;

                boolean isAdmin = p.isAdmin();
                long now = System.currentTimeMillis();

                // Refresh DB cache at most once per 800ms per player.
                boolean mustReload = (economyCacheUuid == null)
                        || !economyCacheUuid.equals(u)
                        || (now - economyCacheLoadedAtMs) > 800L;

                if (mustReload) {
                    Long pid = null;
                    try { pid = repository.getPlotId(u); } catch (Throwable ignored) {}

                    int blockBreaker = 0;
                    int plotBreaker = 0;

                    if (!isAdmin) {
                        try { blockBreaker = repository.getInventoryItemCount(u, "block_cap_breaker"); } catch (Throwable ignored) {}
                        try { plotBreaker  = repository.getInventoryItemCount(u, "plot_size_breaker"); } catch (Throwable ignored) {}
                    }

                    int voidStep = 0;
                    int baseCap = 1;
                    int step = 0;
                    int maxCap = Integer.MAX_VALUE;

                    try { voidStep = safeGetVoidItemCapStep(); } catch (Throwable ignored) {}
                    try { baseCap = safeGetDefaultItemCap(); } catch (Throwable ignored) {}
                    try { step = safeGetItemCapStep(); } catch (Throwable ignored) {}
                    try { maxCap = safeGetMaxItemCap(); } catch (Throwable ignored) {}

                    economyCacheUuid = u;
                    economyCacheLoadedAtMs = now;

                    cachedPlotId = pid;
                    cachedBlockCapBreakerOwned = Math.max(0, blockBreaker);
                    cachedPlotSizeBreakerOwned = Math.max(0, plotBreaker);

                    cachedVoidItemCapStep = Math.max(0, voidStep);
                    cachedDefaultItemCap = Math.max(1, baseCap);
                    cachedItemCapStep = Math.max(0, step);
                    cachedMaxItemCap = (maxCap <= 0 ? Integer.MAX_VALUE : maxCap);
                }
            } finally {
                economyFetchRunning.set(false);
                // Apply labels on EDT (fast, no DB).
                SwingUtilities.invokeLater(this::updateEconomyLabelsForce);
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
        Object sel = topBar.getPlayerSelector().getSelectedItem();

        if (sel instanceof PlayerProfile p) {
            UUID newUuid = p.getPlayerId();
            if (newUuid == null) return;
            if (newUuid.equals(this.currentPlayerUuid)) return;

            UUID oldUuid = this.currentPlayerUuid;

            isSwitching = true;
            setLoading(true);

            runOffEdt(() -> {
                try {
                    try {
                        if (oldUuid != null) gridManager.touchPlayer(oldUuid);
                        onSave.run();
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }

                    safeCloseSession(oldUuid);
                    safeOpenSession(newUuid);

                    gridManager.loadPlotFromDB(newUuid);
                    gridManager.touchPlayer(newUuid);

                    SwingUtilities.invokeLater(() -> {
                        currentPlayerUuid = newUuid;
                        factoryPanel.setPlayerUuid(newUuid);
                        resetEconomyCache();
                        updateTabs();
                        updateLabels();
                        updateEconomyLabelsForce();
                        setLoading(false);
                        isSwitching = false;
                        factoryPanel.requestFocusInWindow();
                    });

                } catch (Exception ex) {
                    ex.printStackTrace();
                    SwingUtilities.invokeLater(() -> {
                        setLoading(false);
                        isSwitching = false;
                    });
                }
            });

        } else if ("--- ADD NEW PLAYER ---".equals(sel)) {
            String n = JOptionPane.showInputDialog("Name:");
            if (n != null && !n.isBlank()) {
                gridManager.createNewPlayer(n);
                refreshPlayerList(true);
            } else {
                refreshPlayerList(false);
            }
        }
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
            if (forceDb) cachedPlayerList = repository.getAllPlayers();

            playerSelector.removeAllItems();
            for (PlayerProfile p : cachedPlayerList) playerSelector.addItem(p);
            playerSelector.addItem("--- ADD NEW PLAYER ---");

            if (currentPlayerUuid != null) {
                for (int i = 0; i < playerSelector.getItemCount(); i++) {
                    Object it = playerSelector.getItemAt(i);
                    if (it instanceof PlayerProfile pp && pp.getPlayerId().equals(currentPlayerUuid)) {
                        playerSelector.setSelectedIndex(i);
                        break;
                    }
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

    private void updateEconomyLabelsForce() {
        resetEconomyCache();
        updateEconomyLabelsIfChanged();
    }

    private void updateEconomyLabelsIfChanged() {
        UUID u = currentPlayerUuid;
        if (u == null) {
            topBar.setPrestigeButtonEnabled(false);
            topBar.setInstantPrestigeButtonEnabled(false);

            topBar.getMoneyLabel().setText("MONEY: $---");
            topBar.getRoleLabel().setText("[---]");
            topBar.getVoidCoinsLabel().setText("VOID: ---");
            topBar.getPrestigeLabel().setText("PRESTIGE: ---");

            statusBar.setPlotId("PLOT ID: ---");
            statusBar.setPlotItemsUnknown();
            statusBar.setPlotAreaUnknown();
            statusBar.setPlotMinusEnabled(false);
            statusBar.setPlotPlusEnabled(false);
            statusBar.setItemCapIncreaseEnabled(false);

            requestEconomyRefreshAsync();

            statusBar.setToolTipText(null);
            return;
        }

        PlayerProfile p = gridManager.getCachedProfile(u);
        if (p == null) return;

        boolean techUnlocked = gridManager.getTechManager().isPrestigeUnlocked(p);
        boolean canPrestige = gridManager.canPerformPrestige(u);
        double cost = gridManager.getPrestigeActionCost(u);

        topBar.setPrestigeButtonEnabled(canPrestige);

        if (!techUnlocked) {
            topBar.setPrestigeButtonToolTip("Unlock the 'Prestige' tech node first.");
        } else if (p.isAdmin()) {
            topBar.setPrestigeButtonToolTip("Prestige unlocked (ADMIN: no fee).");
        } else {
            int fee = (int) Math.round(cost);
            if (!canPrestige) {
                topBar.setPrestigeButtonToolTip("Prestige fee: $" + fee + " (not enough money).");
            } else {
                topBar.setPrestigeButtonToolTip("Prestige fee: $" + fee);
            }
        }


        // instant enabled if item present (or admin)
        topBar.setInstantPrestigeButtonEnabled(gridManager.canInstantPrestige(u));

        double money = p.getMoney();
        String rank = String.valueOf(p.getRank());
        boolean isAdmin = p.isAdmin();
        int voidCoins = p.getVoidCoins();
        int prestige = Math.max(0, p.getPrestigeLevel());

        Long pid = cachedPlotId;

        // IMPORTANT: placed count includes structures (STRUCTURE_GENERIC) via runtime snapshot.
        int placed = computePlacedItemsIncludingStructures(u);

        // IMPORTANT: cap read from core to avoid UI/core mismatch
        int effectiveCap;
        try {
            effectiveCap = gridManager.getEffectiveItemPlacedOnPlotCap(u);
        } catch (Throwable t) {
            // fallback to old formula if core method isn't present for any reason
            int baseCap = safeGetDefaultItemCap();
            int step = safeGetItemCapStep();
            int maxCap = safeGetMaxItemCap();
            effectiveCap = computeEffectiveCap(baseCap, step, maxCap, prestige);
        }



        int voidStep = cachedVoidItemCapStep;

        boolean itemCapPlusEnabled = (voidStep > 0) && (isAdmin || cachedBlockCapBreakerOwned > 0);

        boolean plotPlusEnabled = isAdmin || cachedPlotSizeBreakerOwned > 0;
        boolean plotMinusEnabled = isAdmin; // '-' is admin-only (as before)



        String plotAreaStr = null;
        try {
            GridManager.PlotAreaInfo info = gridManager.getPlotAreaInfo(u);
            if (info != null) {
                plotAreaStr = info.unlockedX() + "x" + info.unlockedY()
                        + " (+" + info.extraX() + "/+" + info.extraY() + ")"
                        + " MAX " + info.maxX() + "x" + info.maxY()
                        + " INC " + info.increaseX() + "x" + info.increaseY();
            }
        } catch (Throwable ignored) {}

        boolean changed =
                Double.compare(money, lastMoneyShown) != 0 ||
                        !Objects.equals(rank, lastRankShown) ||
                        !Objects.equals(pid, lastPlotIdShown) ||
                        (isAdmin != lastAdminShown) ||
                        (voidCoins != lastVoidCoinsShown) ||
                        (prestige != lastPrestigeShown) ||
                        (placed != lastPlotItemsShown) ||
                        (effectiveCap != lastPlotCapShown) ||
                        !Objects.equals(plotAreaStr, lastPlotAreaShown) ||
                        (plotPlusEnabled != lastPlotPlusEnabled) ||
                        (plotMinusEnabled != lastPlotMinusEnabled) ||
                        (itemCapPlusEnabled != lastItemCapPlusEnabled);


        if (!changed) return;

        lastMoneyShown = money;
        lastRankShown = rank;
        lastPlotIdShown = pid;
        lastAdminShown = isAdmin;
        lastVoidCoinsShown = voidCoins;
        lastPrestigeShown = prestige;

        lastPlotItemsShown = placed;
        lastPlotCapShown = effectiveCap;

        lastPlotAreaShown = plotAreaStr;
        lastPlotPlusEnabled = plotPlusEnabled;
        lastPlotMinusEnabled = plotMinusEnabled;

        lastItemCapPlusEnabled = itemCapPlusEnabled;


        topBar.getMoneyLabel().setText(String.format("MONEY: $%,.2f", money));
        topBar.getRoleLabel().setText("[" + rank + "]");
        topBar.getVoidCoinsLabel().setText("VOID: " + voidCoins);
        topBar.getPrestigeLabel().setText("PRESTIGE: " + prestige);

        topBar.getMoneyLabel().setForeground(isAdmin ? new Color(255, 215, 0) : Color.GREEN);
        topBar.getRoleLabel().setForeground(isAdmin ? new Color(255, 215, 0) : Color.LIGHT_GRAY);
        topBar.getVoidCoinsLabel().setForeground(new Color(190, 0, 220));
        topBar.getPrestigeLabel().setForeground(new Color(0, 200, 255));

        statusBar.setPlotId("PLOT ID: #" + (pid != null ? pid : "ERR"));

        // tooltip explains that placed includes structures (runtime count)
        int dbPlaced = repository.getPlotItemsPlaced(u);
        String itemsTooltip =
                "Placed items are counted from the runtime grid snapshot (machines + structures).\n" +
                        "Runtime placed=" + placed + " | DB placed=" + dbPlaced + "\n" +
                        "Cap is read from core. Void step=" + voidStep;

        statusBar.setPlotItems(placed, effectiveCap, itemsTooltip);

        if (plotAreaStr != null) statusBar.setPlotAreaText(plotAreaStr);
        else statusBar.setPlotAreaUnknown();

        statusBar.setPlotMinusEnabled(plotMinusEnabled);
        statusBar.setPlotPlusEnabled(plotPlusEnabled);

        statusBar.setItemCapIncreaseEnabled(itemCapPlusEnabled);

        // status bar tooltip for cap formula (best-effort)
        String tip = "Item cap: (core computed) | void_step=" + voidStep;
        statusBar.setToolTipText(tip);

    }

    private int computePlacedItemsIncludingStructures(UUID ownerId) {
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

        // fallback: DB (background now)
        try {
            return repository.getPlotItemsPlaced(ownerId);
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
