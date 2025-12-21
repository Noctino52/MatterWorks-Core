package com.matterworks.core.infrastructure.swing;

import com.matterworks.core.domain.machines.BlockRegistry;
import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.managers.GridManager;
import com.matterworks.core.ports.IRepository;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MatterWorksGUI extends JFrame {

    private final GridManager gridManager;
    private final BlockRegistry registry;
    private final IRepository repository;
    private final Runnable onSave;

    private final FactoryPanel factoryPanel;
    private final JTabbedPane rightTabbedPane;
    private final JPanel glassPane;

    private JComboBox<Object> playerSelector;
    private JLabel lblMoney, lblTool, lblOrient, lblLayer, lblPlotId;

    private Timer repaintTimer;
    private Timer economyTimer;

    private UUID currentPlayerUuid;
    private Long cachedPlotId;
    private List<PlayerProfile> cachedPlayerList = new ArrayList<>();

    private volatile boolean shuttingDown = false;
    private volatile SwingWorker<Void, Void> activeWorker;

    public MatterWorksGUI(GridManager gm, BlockRegistry reg, UUID initialUuid,
                          Runnable onSave, IRepository repo) {

        this.gridManager = gm;
        this.registry = reg;
        this.repository = repo;
        this.currentPlayerUuid = initialUuid;
        this.onSave = onSave;

        this.factoryPanel = new FactoryPanel(gm, reg, currentPlayerUuid, this::updateLabels);
        this.rightTabbedPane = new JTabbedPane();
        this.rightTabbedPane.setPreferredSize(new Dimension(340, 0));

        this.glassPane = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(new Color(0, 0, 0, 180));
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(Color.WHITE);
                g.setFont(new Font("SansSerif", Font.BOLD, 26));
                String msg = "WORKING...";
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
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                requestShutdown();
            }
        });

        JPanel topContainer = createTopContainer();
        updateTabs();
        JPanel statusBar = createStatusBar();

        add(topContainer, BorderLayout.NORTH);
        add(factoryPanel, BorderLayout.CENTER);
        add(rightTabbedPane, BorderLayout.EAST);
        add(statusBar, BorderLayout.SOUTH);

        repaintTimer = new Timer(80, e -> {
            if (!shuttingDown && factoryPanel.isShowing()) factoryPanel.repaint();
        });

        economyTimer = new Timer(500, e -> {
            if (!shuttingDown) updateEconomyLabels();
        });

        repaintTimer.start();
        economyTimer.start();

        setVisible(true);
        factoryPanel.requestFocusInWindow();

        setLoading(true);
        refreshPlayerListAsync(true, () -> {
            if (currentPlayerUuid != null) {
                startLoadPlayerAsync(currentPlayerUuid, true);
            } else {
                setLoading(false);
            }
        });
    }

    private void requestShutdown() {
        if (shuttingDown) return;
        shuttingDown = true;

        setLoading(true);
        safeStopTimers();

        Component focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (focusOwner != null) {
            try {
                focusOwner.setEnabled(false);
            } catch (Throwable ignored) {
            }
        }

        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                try {
                    onSave.run();
                } catch (Throwable t) {
                    System.err.println("🚨 Shutdown save failed:");
                    t.printStackTrace();
                }
                return null;
            }

            @Override
            protected void done() {
                try {
                    factoryPanel.dispose();
                } catch (Throwable ignored) {}

                for (Component c : rightTabbedPane.getComponents()) {
                    if (c instanceof InventoryDebugPanel p) p.dispose();
                    if (c instanceof TechTreePanel p) p.dispose();
                }

                try {
                    dispose();
                } finally {
                    System.exit(0);
                }
            }
        };

        activeWorker = worker;
        worker.execute();
    }

    private void safeStopTimers() {
        try { if (repaintTimer != null) repaintTimer.stop(); } catch (Throwable ignored) {}
        try { if (economyTimer != null) economyTimer.stop(); } catch (Throwable ignored) {}
    }

    private void setLoading(boolean loading) {
        SwingUtilities.invokeLater(() -> {
            glassPane.setVisible(loading);
            if (playerSelector != null) playerSelector.setEnabled(!loading && !shuttingDown);
        });
    }

    private JPanel createTopContainer() {
        JPanel container = new JPanel(new BorderLayout());

        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 20, 10));
        header.setBackground(new Color(45, 45, 48));

        playerSelector = new JComboBox<>();
        playerSelector.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof PlayerProfile p) {
                    setText(p.getUsername() + "  [" + p.getPlayerId() + "]");
                } else if (value != null) {
                    setText(value.toString());
                }
                return this;
            }
        });

        playerSelector.addActionListener(e -> {
            if (shuttingDown) return;
            if (!glassPane.isVisible()) handlePlayerSwitch();
        });

        lblMoney = createLabel("MONEY: $---", Color.GREEN, 16);

        header.add(new JLabel("ACTIVE USER:") {{ setForeground(Color.WHITE); }});
        header.add(playerSelector);
        header.add(lblMoney);

        JPanel toolbar = new JPanel(new BorderLayout());
        toolbar.setBackground(new Color(60, 60, 65));

        JPanel leftTools = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        leftTools.setOpaque(false);

        leftTools.add(createToolButton("⛏ Drill", "drill_mk1"));
        leftTools.add(createToolButton("➡ Belt", "conveyor_belt"));
        leftTools.add(createToolButton("🔀 Splitter", "splitter"));
        leftTools.add(createToolButton("⭐ Merger", "merger"));
        leftTools.add(createToolButton("⬆ Lift", "lift"));
        leftTools.add(createToolButton("⬇ Drop", "dropper"));
        leftTools.add(createToolButton("🎨 Chromator", "chromator"));
        leftTools.add(createToolButton("🌀 Mixer", "color_mixer"));
        leftTools.add(createToolButton("🔮 Nexus", "nexus_core"));

        leftTools.add(new JSeparator(SwingConstants.VERTICAL) {{ setPreferredSize(new Dimension(5, 25)); }});

        JButton btnStructure = createSimpleButton("🧱 Structure", e -> {
            String blockId = JOptionPane.showInputDialog(this, "Enter Native Block ID (e.g., hytale:stone):", "hytale:stone");
            if (blockId != null && !blockId.isBlank()) {
                factoryPanel.setTool("STRUCTURE:" + blockId);
                updateLabels();
            }
        });
        btnStructure.setBackground(new Color(100, 100, 100));
        leftTools.add(btnStructure);

        leftTools.add(new JSeparator(SwingConstants.VERTICAL) {{ setPreferredSize(new Dimension(5, 25)); }});

        leftTools.add(createSimpleButton("⬇ DOWN", e -> changeLayer(-1)));
        leftTools.add(createSimpleButton("⬆ UP", e -> changeLayer(1)));

        JPanel rightSystem = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 5));
        rightSystem.setOpaque(false);

        JButton btnSOS = createSimpleButton("🆘 SOS", e -> {
            if (currentPlayerUuid == null) return;
            if (gridManager.attemptBailout(currentPlayerUuid)) {
                JOptionPane.showMessageDialog(this, "SOS approved! Funds granted.");
            }
        });
        btnSOS.setBackground(new Color(220, 150, 0));

        JButton btnSave = createSimpleButton("💾 SAVE", e -> {
            if (shuttingDown) return;
            setLoading(true);
            SwingWorker<Void, Void> w = new SwingWorker<>() {
                @Override
                protected Void doInBackground() {
                    try {
                        onSave.run();
                    } catch (Throwable t) {
                        System.err.println("🚨 Manual save failed:");
                        t.printStackTrace();
                    }
                    return null;
                }

                @Override
                protected void done() {
                    setLoading(false);
                    updateEconomyLabels();
                }
            };
            activeWorker = w;
            w.execute();
        });
        btnSave.setBackground(new Color(0, 100, 200));

        JButton btnReset = createSimpleButton("⚠️ RESET", e -> {
            if (currentPlayerUuid == null || shuttingDown) return;
            if (JOptionPane.showConfirmDialog(this, "Reset plot? All progress will be lost.") == JOptionPane.YES_OPTION) {
                setLoading(true);
                SwingWorker<Void, Void> w = new SwingWorker<>() {
                    @Override
                    protected Void doInBackground() {
                        gridManager.resetUserPlot(currentPlayerUuid);
                        return null;
                    }

                    @Override
                    protected void done() {
                        setLoading(false);
                        factoryPanel.forceRefreshNow();
                    }
                };
                activeWorker = w;
                w.execute();
            }
        });
        btnReset.setBackground(new Color(180, 0, 0));

        JButton btnDelete = createSimpleButton("💀 DELETE", e -> handleDeletePlayer());
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

    private void handleDeletePlayer() {
        if (currentPlayerUuid == null || shuttingDown) return;

        int confirm = JOptionPane.showConfirmDialog(this,
                "PERMANENTLY DELETE THIS PLAYER?",
                "Delete Player", JOptionPane.YES_NO_OPTION, JOptionPane.ERROR_MESSAGE);

        if (confirm != JOptionPane.YES_OPTION) return;

        setLoading(true);
        UUID toDelete = currentPlayerUuid;

        SwingWorker<Void, Void> w = new SwingWorker<>() {
            private List<PlayerProfile> players;
            private UUID nextUuid;

            @Override
            protected Void doInBackground() {
                gridManager.deletePlayer(toDelete);

                players = repository.getAllPlayers();
                if (players != null && !players.isEmpty()) {
                    nextUuid = players.get(0).getPlayerId();
                    cachedPlotId = repository.getPlotId(nextUuid);
                    gridManager.loadPlotFromDB(nextUuid);
                } else {
                    nextUuid = null;
                    cachedPlotId = null;
                }
                return null;
            }

            @Override
            protected void done() {
                cachedPlayerList = (players != null) ? players : new ArrayList<>();
                rebuildPlayerSelectorModel();

                currentPlayerUuid = nextUuid;
                factoryPanel.setPlayerUuid(nextUuid);

                updateTabs();
                updateLabels();
                updateEconomyLabels();

                setLoading(false);
            }
        };

        activeWorker = w;
        w.execute();
    }

    private JPanel createStatusBar() {
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.setBackground(new Color(35, 35, 35));

        JPanel leftStatus = new JPanel(new FlowLayout(FlowLayout.LEFT, 20, 5));
        leftStatus.setOpaque(false);

        lblTool = createLabel("TOOL: Drill", Color.WHITE, 12);
        lblOrient = createLabel("DIR: NORTH", Color.WHITE, 12);
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
            if (c instanceof InventoryDebugPanel p) p.dispose();
            if (c instanceof TechTreePanel p) p.dispose();
        }

        rightTabbedPane.removeAll();

        if (currentPlayerUuid != null) {
            rightTabbedPane.addTab("Shop", new InventoryDebugPanel(repository, currentPlayerUuid, gridManager));
            rightTabbedPane.addTab("Tech Tree", new TechTreePanel(repository, currentPlayerUuid, gridManager));
        } else {
            rightTabbedPane.addTab("Info", new JPanel() {{ add(new JLabel("No Player Selected")); }});
        }
    }

    private void handlePlayerSwitch() {
        Object sel = playerSelector.getSelectedItem();

        if (sel instanceof PlayerProfile p) {
            UUID newUuid = p.getPlayerId();
            if (newUuid != null && newUuid.equals(this.currentPlayerUuid)) return;
            if (newUuid == null) return;

            startLoadPlayerAsync(newUuid, false);
            return;
        }

        if ("--- ADD NEW PLAYER ---".equals(sel)) {
            String n = JOptionPane.showInputDialog(this, "Name:");
            if (n == null || n.isBlank()) {
                rebuildPlayerSelectorModel();
                return;
            }

            setLoading(true);
            SwingWorker<Void, Void> w = new SwingWorker<>() {
                private List<PlayerProfile> players;

                @Override
                protected Void doInBackground() {
                    gridManager.createNewPlayer(n);
                    players = repository.getAllPlayers();
                    return null;
                }

                @Override
                protected void done() {
                    cachedPlayerList = (players != null) ? players : new ArrayList<>();
                    rebuildPlayerSelectorModel();
                    setLoading(false);
                }
            };
            activeWorker = w;
            w.execute();
        }
    }

    private void startLoadPlayerAsync(UUID newUuid, boolean initialLoad) {
        if (shuttingDown) return;

        setLoading(true);

        SwingWorker<Void, Void> w = new SwingWorker<>() {
            private Long plotId;
            private Throwable error;

            @Override
            protected Void doInBackground() {
                try {
                    gridManager.loadPlotFromDB(newUuid);
                    plotId = repository.getPlotId(newUuid);
                } catch (Throwable t) {
                    error = t;
                }
                return null;
            }

            @Override
            protected void done() {
                if (error != null) {
                    error.printStackTrace();
                    JOptionPane.showMessageDialog(MatterWorksGUI.this,
                            "Failed to load plot for player: " + newUuid,
                            "Load Error",
                            JOptionPane.ERROR_MESSAGE);
                    setLoading(false);
                    if (initialLoad) requestShutdown();
                    return;
                }

                currentPlayerUuid = newUuid;
                cachedPlotId = plotId;

                factoryPanel.setPlayerUuid(newUuid);
                factoryPanel.forceRefreshNow();

                updateTabs();
                updateLabels();
                updateEconomyLabels();

                setLoading(false);
            }
        };

        activeWorker = w;
        w.execute();
    }

    private void refreshPlayerListAsync(boolean forceDb, Runnable after) {
        if (shuttingDown) return;

        if (!forceDb) {
            rebuildPlayerSelectorModel();
            if (after != null) after.run();
            return;
        }

        SwingWorker<Void, Void> w = new SwingWorker<>() {
            private List<PlayerProfile> players;

            @Override
            protected Void doInBackground() {
                try {
                    players = repository.getAllPlayers();
                } catch (Throwable t) {
                    t.printStackTrace();
                    players = new ArrayList<>();
                }
                return null;
            }

            @Override
            protected void done() {
                cachedPlayerList = (players != null) ? players : new ArrayList<>();
                rebuildPlayerSelectorModel();
                if (after != null) after.run();
            }
        };

        activeWorker = w;
        w.execute();
    }

    private void rebuildPlayerSelectorModel() {
        if (playerSelector == null) return;

        ActionListener[] listeners = playerSelector.getActionListeners();
        for (ActionListener l : listeners) playerSelector.removeActionListener(l);

        playerSelector.removeAllItems();
        for (PlayerProfile p : cachedPlayerList) playerSelector.addItem(p);
        playerSelector.addItem("--- ADD NEW PLAYER ---");

        if (currentPlayerUuid != null) {
            for (int i = 0; i < playerSelector.getItemCount(); i++) {
                Object it = playerSelector.getItemAt(i);
                if (it instanceof PlayerProfile p && currentPlayerUuid.equals(p.getPlayerId())) {
                    playerSelector.setSelectedIndex(i);
                    break;
                }
            }
        }

        for (ActionListener l : listeners) playerSelector.addActionListener(l);
    }

    private void updateEconomyLabels() {
        if (currentPlayerUuid == null) {
            lblMoney.setText("MONEY: $---");
            lblPlotId.setText("PLOT ID: ---");
            return;
        }

        PlayerProfile p = gridManager.getCachedProfile(currentPlayerUuid);
        if (p != null) {
            lblMoney.setText(String.format("MONEY: $%,.2f [%s]", p.getMoney(), p.getRank()));
            lblMoney.setForeground(p.isAdmin() ? new Color(255, 215, 0) : Color.GREEN);
        }

        if (cachedPlotId != null) {
            lblPlotId.setText("PLOT ID: #" + cachedPlotId);
        } else {
            lblPlotId.setText("PLOT ID: #---");
        }
    }

    private void updateLabels() {
        if (lblTool != null) {
            String t = factoryPanel.getCurrentToolName();
            if (t.startsWith("STRUCTURE:")) t = "STRUCT (" + t.substring(10) + ")";
            lblTool.setText("TOOL: " + t);
            lblOrient.setText("DIR: " + factoryPanel.getCurrentOrientationName());
            lblLayer.setText("LAYER: " + factoryPanel.getCurrentLayer());
        }
    }

    private void changeLayer(int delta) {
        int newY = Math.max(0, factoryPanel.getCurrentLayer() + delta);
        factoryPanel.setLayer(newY);
        updateLabels();
    }

    private JButton createToolButton(String text, String itemId) {
        JButton btn = createSimpleButton(text, e -> {
            factoryPanel.setTool(itemId);
            updateLabels();
        });

        btn.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (shuttingDown) return;
                if (SwingUtilities.isRightMouseButton(e) && currentPlayerUuid != null) {
                    gridManager.buyItem(currentPlayerUuid, itemId, 1);
                }
            }
        });

        return btn;
    }

    private JButton createSimpleButton(String text, java.awt.event.ActionListener action) {
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
}
