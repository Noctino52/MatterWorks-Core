package com.matterworks.core.infrastructure.swing;

import com.matterworks.core.infrastructure.MariaDBAdapter;
import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.domain.machines.BlockRegistry;
import com.matterworks.core.managers.GridManager;
import com.matterworks.core.ports.IRepository;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MatterWorksGUI extends JFrame {

    private final GridManager gridManager;
    private final BlockRegistry registry;
    private final IRepository repository;
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

    private volatile double lastMoneyShown = Double.NaN;
    private volatile String lastRankShown = null;
    private volatile Long lastPlotIdShown = null;
    private volatile boolean lastAdminShown = false;

    public MatterWorksGUI(GridManager gm,
                          BlockRegistry reg,
                          UUID initialUuid,
                          Runnable onSave,
                          IRepository repo) {

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
            @Override protected void paintComponent(Graphics g) {
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

        // top bar callbacks
        this.topBar = new TopBarPanel(
                this::selectTool,
                this::buyToolRightClick,
                this::changeLayer,
                this::selectStructureTool,
                this::handleSOS,
                this::handleSave,
                this::handleReset,
                this::handleDeletePlayer
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

    private void requestEconomyRefresh() {
        SwingUtilities.invokeLater(this::updateEconomyLabelsForce);
    }

    // ===== Tabs =====

    private void updateTabs() {
        for (Component c : rightTabbedPane.getComponents()) {
            if (c instanceof InventoryDebugPanel p) p.dispose();
            if (c instanceof TechTreePanel p) p.dispose();
        }

        rightTabbedPane.removeAll();

        if (currentPlayerUuid != null) {
            rightTabbedPane.addTab("Shop", new InventoryDebugPanel(repository, currentPlayerUuid, gridManager, this::requestEconomyRefresh));
            rightTabbedPane.addTab("Tech Tree", new TechTreePanel(repository, currentPlayerUuid, gridManager));
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

    private void safeOpenSession(UUID uuid) {
        if (uuid == null) return;
        if (repository instanceof MariaDBAdapter a) a.openPlayerSession(uuid);
    }

    private void safeCloseSession(UUID uuid) {
        if (uuid == null) return;
        if (repository instanceof MariaDBAdapter a) a.closePlayerSession(uuid);
    }

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
    }

    private void updateEconomyLabelsForce() {
        resetEconomyCache();
        updateEconomyLabelsIfChanged();
    }

    private void updateEconomyLabelsIfChanged() {
        UUID u = currentPlayerUuid;
        if (u == null) {
            topBar.getMoneyLabel().setText("MONEY: $---");
            statusBar.setPlotId("PLOT ID: ---");
            return;
        }

        PlayerProfile p = gridManager.getCachedProfile(u);
        if (p == null) return;

        double money = p.getMoney();
        String rank = String.valueOf(p.getRank());
        boolean isAdmin = p.isAdmin();

        Long pid = repository.getPlotId(u);

        boolean changed =
                Double.compare(money, lastMoneyShown) != 0 ||
                        (rank != null && !rank.equals(lastRankShown)) ||
                        (pid != null ? !pid.equals(lastPlotIdShown) : lastPlotIdShown != null) ||
                        (isAdmin != lastAdminShown);

        if (!changed) return;

        lastMoneyShown = money;
        lastRankShown = rank;
        lastPlotIdShown = pid;
        lastAdminShown = isAdmin;

        topBar.getMoneyLabel().setText(String.format("MONEY: $%,.2f [%s]", money, rank));
        topBar.getMoneyLabel().setForeground(isAdmin ? new Color(255, 215, 0) : Color.GREEN);

        statusBar.setPlotId("PLOT ID: #" + (pid != null ? pid : "ERR"));
    }

    private void updateLabels() {
        String t = factoryPanel.getCurrentToolName();
        if (t != null && t.startsWith("STRUCTURE:")) t = "STRUCT (" + t.substring(10) + ")";

        statusBar.setTool(t);
        statusBar.setDir(factoryPanel.getCurrentOrientationName());
        statusBar.setLayer(factoryPanel.getCurrentLayer());
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
}
