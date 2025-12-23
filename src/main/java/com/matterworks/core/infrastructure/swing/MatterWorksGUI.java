package com.matterworks.core.infrastructure.swing;

import com.matterworks.core.domain.machines.BlockRegistry;
import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.infrastructure.MariaDBAdapter;
import com.matterworks.core.managers.GridManager;
import com.matterworks.core.ports.IRepository;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
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
    private final JTabbedPane rightTabbedPane;

    private final JPanel glassPane;
    private JComboBox<Object> playerSelector;

    private JLabel lblMoney;
    private JLabel lblTool;
    private JLabel lblOrient;
    private JLabel lblLayer;
    private JLabel lblPlotId;

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

        this.rightTabbedPane = new JTabbedPane();
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
        glassPane.addMouseListener(new MouseAdapter() {});
        glassPane.addMouseMotionListener(new MouseAdapter() {});
        setGlassPane(glassPane);

        setTitle("MatterWorks Architect - Multi-User Management");
        setSize(1480, 900);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

        JPanel topContainer = createTopContainer();
        updateTabs();
        JPanel statusBar = createStatusBar();

        add(topContainer, BorderLayout.NORTH);
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
            @Override
            public void windowClosing(WindowEvent e) {
                shutdownAndExit();
            }
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

    private void requestEconomyRefresh() {
        SwingUtilities.invokeLater(this::updateEconomyLabelsForce);
    }

    // ==========================================================
    // UI CONSTRUCTION
    // ==========================================================

    private JPanel createTopContainer() {
        JPanel container = new JPanel(new BorderLayout());

        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 20, 10));
        header.setBackground(new Color(45, 45, 48));

        JLabel activeLabel = new JLabel("ACTIVE USER:");
        activeLabel.setForeground(Color.WHITE);

        playerSelector = new JComboBox<>();
        refreshPlayerList(true);

        playerSelector.addActionListener(e -> {
            if (suppressPlayerEvents) return;
            if (!glassPane.isVisible() && !isSwitching) handlePlayerSwitch();
        });

        lblMoney = createLabel("MONEY: $---", Color.GREEN, 16);

        header.add(activeLabel);
        header.add(playerSelector);
        header.add(lblMoney);

        JPanel toolbar = new JPanel(new BorderLayout());
        toolbar.setBackground(new Color(60, 60, 65));

        JPanel leftTools = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        leftTools.setOpaque(false);

        leftTools.add(createToolButton("Drill", "drill_mk1"));
        leftTools.add(createToolButton("Belt", "conveyor_belt"));
        leftTools.add(createToolButton("Splitter", "splitter"));
        leftTools.add(createToolButton("Merger", "merger"));
        leftTools.add(createToolButton("Lift", "lift"));
        leftTools.add(createToolButton("Drop", "dropper"));
        leftTools.add(createToolButton("Chromator", "chromator"));
        leftTools.add(createToolButton("Mixer", "color_mixer"));
        leftTools.add(createToolButton("Nexus", "nexus_core"));

        leftTools.add(new JSeparator(SwingConstants.VERTICAL) {{
            setPreferredSize(new Dimension(5, 25));
        }});

        JButton btnStructure = createSimpleButton("Structure", e -> {
            String blockId = JOptionPane.showInputDialog(
                    this,
                    "Enter Native Block ID (e.g., hytale:stone):",
                    "hytale:stone"
            );
            if (blockId != null && !blockId.isBlank()) {
                factoryPanel.setTool("STRUCTURE:" + blockId);
                factoryPanel.requestFocusInWindow();
                updateLabels();
            }
        });
        btnStructure.setBackground(new Color(100, 100, 100));
        leftTools.add(btnStructure);

        leftTools.add(new JSeparator(SwingConstants.VERTICAL) {{
            setPreferredSize(new Dimension(5, 25));
        }});

        leftTools.add(createSimpleButton("DOWN", e -> changeLayer(-1)));
        leftTools.add(createSimpleButton("UP", e -> changeLayer(1)));

        JPanel rightSystem = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 5));
        rightSystem.setOpaque(false);

        JButton btnSOS = createSimpleButton("SOS", e -> {
            if (currentPlayerUuid == null) return;
            gridManager.touchPlayer(currentPlayerUuid);
            if (gridManager.attemptBailout(currentPlayerUuid)) {
                JOptionPane.showMessageDialog(this, "SOS approved! Funds granted.");
                updateEconomyLabelsForce();
            }
        });
        btnSOS.setBackground(new Color(220, 150, 0));

        JButton btnSave = createSimpleButton("SAVE", e -> {
            if (currentPlayerUuid != null) gridManager.touchPlayer(currentPlayerUuid);
            onSave.run();
            updateEconomyLabelsForce();
        });
        btnSave.setBackground(new Color(0, 100, 200));

        JButton btnReset = createSimpleButton("RESET", e -> {
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
        });
        btnReset.setBackground(new Color(180, 0, 0));

        JButton btnDelete = createSimpleButton("DELETE", e -> handleDeletePlayer());
        btnDelete.setBackground(Color.BLACK);
        btnDelete.setForeground(Color.RED);
        btnDelete.setBorder(BorderFactory.createLineBorder(Color.RED, 1));

        rightSystem.add(btnSOS);
        rightSystem.add(btnSave);
        rightSystem.add(btnReset);
        rightSystem.add(btnDelete);

        toolbar.add(leftTools, BorderLayout.WEST);
        toolbar.add(rightSystem, BorderLayout.EAST);

        container.add(header, BorderLayout.NORTH);
        container.add(toolbar, BorderLayout.SOUTH);
        return container;
    }

    private JPanel createStatusBar() {
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.setBackground(new Color(35, 35, 35));

        JPanel leftStatus = new JPanel(new FlowLayout(FlowLayout.LEFT, 20, 5));
        leftStatus.setOpaque(false);

        lblTool = createLabel("TOOL: ---", Color.WHITE, 12);
        lblOrient = createLabel("DIR: ---", Color.WHITE, 12);
        lblLayer = createLabel("LAYER: 0", Color.CYAN, 12);

        leftStatus.add(lblTool);
        leftStatus.add(lblOrient);
        leftStatus.add(lblLayer);

        lblPlotId = createLabel("PLOT ID: #---", Color.LIGHT_GRAY, 12);

        statusPanel.add(leftStatus, BorderLayout.WEST);
        statusPanel.add(lblPlotId, BorderLayout.EAST);

        return statusPanel;
    }

    private void updateTabs() {
        for (Component c : rightTabbedPane.getComponents()) {
            if (c instanceof InventoryDebugPanel) ((InventoryDebugPanel) c).dispose();
            if (c instanceof TechTreePanel) ((TechTreePanel) c).dispose();
        }

        rightTabbedPane.removeAll();

        if (currentPlayerUuid != null) {
            rightTabbedPane.addTab("Shop", new InventoryDebugPanel(repository, currentPlayerUuid, gridManager, this::requestEconomyRefresh));
            rightTabbedPane.addTab("Tech Tree", new TechTreePanel(repository, currentPlayerUuid, gridManager));
        } else {
            rightTabbedPane.addTab("Info", new JPanel() {{
                add(new JLabel("No Player Selected"));
            }});
        }
    }

    // ==========================================================
    // SWITCHING / SESSION
    // ==========================================================

    private void handlePlayerSwitch() {
        Object sel = playerSelector.getSelectedItem();

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
        if (repository instanceof MariaDBAdapter a) {
            a.openPlayerSession(uuid);
        }
    }

    private void safeCloseSession(UUID uuid) {
        if (uuid == null) return;
        if (repository instanceof MariaDBAdapter a) {
            a.closePlayerSession(uuid);
        }
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

    // ==========================================================
    // LIST / LABELS / UTILS
    // ==========================================================

    private void refreshPlayerList(boolean forceDb) {
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
        if (lblMoney == null || lblPlotId == null) return;

        UUID u = currentPlayerUuid;
        if (u == null) {
            lblMoney.setText("MONEY: $---");
            lblPlotId.setText("PLOT ID: ---");
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

        lblMoney.setText(String.format("MONEY: $%,.2f [%s]", money, rank));
        lblMoney.setForeground(isAdmin ? new Color(255, 215, 0) : Color.GREEN);

        lblPlotId.setText("PLOT ID: #" + (pid != null ? pid : "ERR"));
    }

    private void updateLabels() {
        if (lblTool == null) return;

        String t = factoryPanel.getCurrentToolName();
        if (t != null && t.startsWith("STRUCTURE:")) t = "STRUCT (" + t.substring(10) + ")";

        lblTool.setText("TOOL: " + (t != null ? t : "---"));
        lblOrient.setText("DIR: " + factoryPanel.getCurrentOrientationName());
        lblLayer.setText("LAYER: " + factoryPanel.getCurrentLayer());
    }

    private void changeLayer(int delta) {
        int newY = Math.max(0, factoryPanel.getCurrentLayer() + delta);
        factoryPanel.setLayer(newY);
        factoryPanel.requestFocusInWindow();
        updateLabels();
    }

    private JButton createToolButton(String text, String itemId) {
        JButton btn = createSimpleButton(text, e -> {
            factoryPanel.setTool(itemId);
            factoryPanel.requestFocusInWindow();
            updateLabels();
            if (currentPlayerUuid != null) gridManager.touchPlayer(currentPlayerUuid);
        });

        // buy 1 item con right-click -> OFF-EDT (DB)
        btn.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e) && currentPlayerUuid != null) {
                    UUID u = currentPlayerUuid;
                    runOffEdt(() -> {
                        gridManager.touchPlayer(u);
                        boolean ok = gridManager.buyItem(u, itemId, 1);
                        if (ok) SwingUtilities.invokeLater(() -> updateEconomyLabelsForce());
                    });
                }
            }
        });

        return btn;
    }

    private JButton createSimpleButton(String text, ActionListener action) {
        JButton btn = new JButton(text);
        btn.setFocusable(false);
        btn.addActionListener(action);
        btn.setBackground(new Color(70, 70, 70));
        btn.setForeground(Color.WHITE);
        return btn;
    }

    private JLabel createLabel(String text, Color color, int size) {
        JLabel lbl = new JLabel(text);
        lbl.setForeground(color);
        lbl.setFont(new Font("Monospaced", Font.BOLD, size));
        return lbl;
    }

    private void setLoading(boolean loading) {
        SwingUtilities.invokeLater(() -> {
            glassPane.setVisible(loading);
            if (playerSelector != null) playerSelector.setEnabled(!loading);
        });
    }

    private void runOffEdt(Runnable r) {
        worker.submit(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                t.printStackTrace();
            }
        });
    }
}
