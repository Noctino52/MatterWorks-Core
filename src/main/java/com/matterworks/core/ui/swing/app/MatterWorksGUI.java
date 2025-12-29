package com.matterworks.core.ui.swing.app;

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

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private volatile boolean lastPlotResizeEnabled = false;

    // NEW: per instant button refresh (non serve cache separata: ricalcoliamo sempre)
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

        // ✅ usa il costruttore a 10 parametri: classic + instant
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
                this::handleDeletePlayer
        );

        // plot resize buttons (admin only; dominio fa i controlli)
        statusBar.setPlotResizeActions(
                () -> handlePlotResize(-1),
                () -> handlePlotResize(+1)
        );

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

        economyTimer = new Timer(600, e -> updateEconomyLabelsIfChanged());
        heartbeatTimer = new Timer(10_000, e -> {
            UUID u = currentPlayerUuid;
            if (u != null) gridManager.touchPlayer(u);
        });

        economyTimer.start();
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
        if (currentPlayerUuid == null) return;
        gridManager.touchPlayer(currentPlayerUuid);
        if (gridManager.attemptBailout(currentPlayerUuid)) {
            JOptionPane.showMessageDialog(this, "SOS approved! Funds granted.");
            updateEconomyLabelsForce();
        }
    }

    private void handleSave() {
        if (currentPlayerUuid != null) gridManager.touchPlayer(currentPlayerUuid);
        onSave.run();
        updateEconomyLabelsForce();
    }

    private void handleReset() {
        if (currentPlayerUuid == null) return;
        int res = JOptionPane.showConfirmDialog(
                this,
                "Reset plot? All progress will be lost.",
                "Reset",
                JOptionPane.YES_NO_OPTION
        );
        if (res == JOptionPane.YES_OPTION) {
            gridManager.touchPlayer(currentPlayerUuid);
            gridManager.resetUserPlot(currentPlayerUuid);
            factoryPanel.forceRefreshNow();
            updateEconomyLabelsForce();
        }
    }

    private void handlePrestige() {
        UUID u = currentPlayerUuid;
        if (u == null) return;

        PlayerProfile p = gridManager.getCachedProfile(u);
        if (p == null) return;

        boolean unlocked = gridManager.getTechManager().isPrestigeUnlocked(p);
        if (!unlocked) {
            JOptionPane.showMessageDialog(this,
                    "Prestige is locked. Finish the Tech Tree first.",
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

        String msg =
                "Advance to PRESTIGE " + nextPrestige + "?\n\n" +
                        "This will:\n" +
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

    // ✅ NEW: Instant Prestige (premium, NO reset)
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
        if (p == null || !p.isAdmin()) return;

        boolean ok;
        if (dir > 0) ok = gridManager.increasePlotUnlockedArea(u);
        else ok = gridManager.decreasePlotUnlockedArea(u);

        if (!ok) {
            String msg = (dir > 0)
                    ? "Impossibile espandere: già al MAX o non consentito."
                    : "Impossibile ridurre: ci sono macchine fuori dalla nuova area o sei già allo START.";
            JOptionPane.showMessageDialog(this, msg, "Plot Resize", JOptionPane.WARNING_MESSAGE);
            return;
        }

        factoryPanel.forceRefreshNow();
        updateEconomyLabelsForce();
    }

    private void requestEconomyRefresh() {
        SwingUtilities.invokeLater(this::updateEconomyLabelsForce);
    }

    // ===== Tabs =====

    private void updateTabs() {
        for (Component c : rightTabbedPane.getComponents()) {
            if (c instanceof InventoryDebugPanel p) p.dispose();
            if (c instanceof TechTreePanel p) p.dispose();
            if (c instanceof VoidShopPanel p) p.dispose();
        }

        rightTabbedPane.removeAll();

        if (currentPlayerUuid != null) {
            rightTabbedPane.addTab("Shop", new InventoryDebugPanel(repository, currentPlayerUuid, gridManager, this::requestEconomyRefresh));
            rightTabbedPane.addTab("Tech Tree", new TechTreePanel(repository, currentPlayerUuid, gridManager));
            rightTabbedPane.addTab("Void Shop", new VoidShopPanel(repository, currentPlayerUuid, gridManager, this::requestEconomyRefresh));
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
        lastPlotResizeEnabled = false;
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
            statusBar.setPlotResizeEnabled(false);
            statusBar.setToolTipText(null);
            return;
        }

        PlayerProfile p = gridManager.getCachedProfile(u);
        if (p == null) return;

        boolean prestigeBtnEnabled = gridManager.getTechManager().isPrestigeUnlocked(p);
        topBar.setPrestigeButtonEnabled(prestigeBtnEnabled);

        // ✅ NEW: instant enabled if item present (or admin)
        topBar.setInstantPrestigeButtonEnabled(gridManager.canInstantPrestige(u));

        double money = p.getMoney();
        String rank = String.valueOf(p.getRank());
        boolean isAdmin = p.isAdmin();
        int voidCoins = p.getVoidCoins();
        int prestige = Math.max(0, p.getPrestigeLevel());

        Long pid = repository.getPlotId(u);
        int placed = repository.getPlotItemsPlaced(u);

        int baseCap = safeGetDefaultItemCap();
        int step = safeGetItemCapStep();
        int maxCap = safeGetMaxItemCap();
        int effectiveCap = computeEffectiveCap(baseCap, step, maxCap, prestige);

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
                        (lastPlotResizeEnabled != isAdmin);

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
        lastPlotResizeEnabled = isAdmin;

        topBar.getMoneyLabel().setText(String.format("MONEY: $%,.2f", money));
        topBar.getRoleLabel().setText("[" + rank + "]");
        topBar.getVoidCoinsLabel().setText("VOID: " + voidCoins);
        topBar.getPrestigeLabel().setText("PRESTIGE: " + prestige);

        topBar.getMoneyLabel().setForeground(isAdmin ? new Color(255, 215, 0) : Color.GREEN);
        topBar.getRoleLabel().setForeground(isAdmin ? new Color(255, 215, 0) : Color.LIGHT_GRAY);
        topBar.getVoidCoinsLabel().setForeground(new Color(190, 0, 220));
        topBar.getPrestigeLabel().setForeground(new Color(0, 200, 255));

        statusBar.setPlotId("PLOT ID: #" + (pid != null ? pid : "ERR"));
        statusBar.setPlotItems(placed, effectiveCap);

        if (plotAreaStr != null) statusBar.setPlotAreaText(plotAreaStr);
        else statusBar.setPlotAreaUnknown();

        statusBar.setPlotResizeEnabled(isAdmin);

        String tip = "Item cap: base " + Math.max(1, baseCap)
                + " + prestige(" + prestige + ")×" + Math.max(0, step)
                + (maxCap != Integer.MAX_VALUE ? (" (max " + maxCap + ")") : "")
                + " -> " + effectiveCap;
        statusBar.setToolTipText(tip);
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
