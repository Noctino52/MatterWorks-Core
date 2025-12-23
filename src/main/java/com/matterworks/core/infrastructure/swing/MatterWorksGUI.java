package com.matterworks.core.infrastructure.swing;

import com.matterworks.core.domain.machines.BlockRegistry;
import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.infrastructure.MariaDBAdapter;
import com.matterworks.core.managers.GridManager;
import com.matterworks.core.ports.IRepository;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
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

    private JLabel lblMoney;
    private JLabel lblTool;
    private JLabel lblOrient;
    private JLabel lblLayer;
    private JLabel lblPlotId;

    private final Timer repaintTimer;
    private final Timer economyTimer;
    private final Timer heartbeatTimer;

    private UUID currentPlayerUuid;
    private List<PlayerProfile> cachedPlayerList = new ArrayList<>();

    private volatile boolean isSwitching = false;

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

        this.factoryPanel = new FactoryPanel(gridManager, registry, currentPlayerUuid, this::updateLabels);

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

        repaintTimer = new Timer(40, e -> factoryPanel.repaint());
        economyTimer = new Timer(1000, e -> updateEconomyLabels());
        heartbeatTimer = new Timer(10_000, e -> {
            if (currentPlayerUuid != null) gridManager.touchPlayer(currentPlayerUuid);
        });

        repaintTimer.start();
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
        updateEconomyLabels();

        if (currentPlayerUuid != null) {
            // startup: carico plot e apro sessione come "active user"
            runOffEdt(() -> {
                safeOpenSession(currentPlayerUuid);
                gridManager.loadPlotFromDB(currentPlayerUuid);
                gridManager.touchPlayer(currentPlayerUuid);
            });
        }
    }

    // ==========================================================
    // UI CONSTRUCTION
    // ==========================================================

    private JPanel createTopContainer() {
        JPanel container = new JPanel(new BorderLayout());

        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 20, 10));
        header.setBackground(new Color(45, 45, 48));

        playerSelector = new JComboBox<>();
        refreshPlayerList(true);

        playerSelector.addActionListener(e -> {
            if (!glassPane.isVisible() && !isSwitching) handlePlayerSwitch();
        });

        lblMoney = createLabel("MONEY: $---", Color.GREEN, 16);

        JLabel activeLabel = new JLabel("ACTIVE USER:");
        activeLabel.setForeground(Color.WHITE);

        header.add(activeLabel);
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

        leftTools.add(new JSeparator(SwingConstants.VERTICAL) {{
            setPreferredSize(new Dimension(5, 25));
        }});

        JButton btnStructure = createSimpleButton("🧱 Structure", e -> {
            String blockId = JOptionPane.showInputDialog(
                    this,
                    "Enter Native Block ID (e.g., hytale:stone):",
                    "hytale:stone"
            );
            if (blockId != null && !blockId.isBlank()) {
                factoryPanel.setTool("STRUCTURE:" + blockId);
                updateLabels();
            }
        });
        btnStructure.setBackground(new Color(100, 100, 100));
        leftTools.add(btnStructure);

        leftTools.add(new JSeparator(SwingConstants.VERTICAL) {{
            setPreferredSize(new Dimension(5, 25));
        }});

        leftTools.add(createSimpleButton("⬇ DOWN", e -> changeLayer(-1)));
        leftTools.add(createSimpleButton("⬆ UP", e -> changeLayer(1)));

        JPanel rightSystem = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 5));
        rightSystem.setOpaque(false);

        JButton btnSOS = createSimpleButton("🆘 SOS", e -> {
            if (currentPlayerUuid == null) return;
            gridManager.touchPlayer(currentPlayerUuid);
            if (gridManager.attemptBailout(currentPlayerUuid)) {
                JOptionPane.showMessageDialog(this, "SOS approved! Funds granted.");
            }
        });
        btnSOS.setBackground(new Color(220, 150, 0));

        JButton btnSave = createSimpleButton("💾 SAVE", e -> {
            if (currentPlayerUuid != null) gridManager.touchPlayer(currentPlayerUuid);
            onSave.run();
        });
        btnSave.setBackground(new Color(0, 100, 200));

        JButton btnReset = createSimpleButton("⚠️ RESET", e -> {
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
            if (c instanceof InventoryDebugPanel) ((InventoryDebugPanel) c).dispose();
            if (c instanceof TechTreePanel) ((TechTreePanel) c).dispose();
        }

        rightTabbedPane.removeAll();

        if (currentPlayerUuid != null) {
            rightTabbedPane.addTab("Shop", new InventoryDebugPanel(repository, currentPlayerUuid, gridManager));
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
                    // 1) salva prima di cambiare (riduce rischio di "parziali")
                    try {
                        if (oldUuid != null) gridManager.touchPlayer(oldUuid);
                        onSave.run();
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }

                    // 2) chiudi sessione old
                    safeCloseSession(oldUuid);

                    // 3) apri sessione new
                    safeOpenSession(newUuid);

                    // 4) carica plot new + mark activity
                    gridManager.loadPlotFromDB(newUuid);
                    gridManager.touchPlayer(newUuid);

                    SwingUtilities.invokeLater(() -> {
                        this.currentPlayerUuid = newUuid;
                        factoryPanel.setPlayerUuid(newUuid);
                        updateTabs();
                        updateLabels();
                        updateEconomyLabels();
                        setLoading(false);
                        isSwitching = false;
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
                // salva prima
                try {
                    onSave.run();
                } catch (Exception ex) {
                    ex.printStackTrace();
                }

                // chiudi sessione del player eliminato
                safeCloseSession(toDelete);

                // elimina
                gridManager.deletePlayer(toDelete);

                SwingUtilities.invokeLater(() -> {
                    refreshPlayerList(true);

                    if (!cachedPlayerList.isEmpty()) {
                        PlayerProfile next = cachedPlayerList.get(0);
                        UUID nextId = next.getPlayerId();

                        this.currentPlayerUuid = nextId;
                        factoryPanel.setPlayerUuid(nextId);

                        runOffEdt(() -> {
                            safeOpenSession(nextId);
                            gridManager.loadPlotFromDB(nextId);
                            gridManager.touchPlayer(nextId);

                            SwingUtilities.invokeLater(() -> {
                                updateTabs();
                                updateLabels();
                                updateEconomyLabels();
                                setLoading(false);
                                isSwitching = false;
                            });
                        });

                    } else {
                        this.currentPlayerUuid = null;
                        factoryPanel.setPlayerUuid(null);
                        updateTabs();
                        updateLabels();
                        updateEconomyLabels();
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
                try {
                    // IMPORTANTISSIMO: salva prima di chiudere
                    onSave.run();
                } catch (Exception ex) {
                    ex.printStackTrace();
                }

                try {
                    safeCloseSession(currentPlayerUuid);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }

                SwingUtilities.invokeLater(() -> {
                    try { repaintTimer.stop(); } catch (Exception ignored) {}
                    try { economyTimer.stop(); } catch (Exception ignored) {}
                    try { heartbeatTimer.stop(); } catch (Exception ignored) {}

                    try { dispose(); } catch (Exception ignored) {}
                    System.exit(0);
                });

            } catch (Exception ex) {
                ex.printStackTrace();
                SwingUtilities.invokeLater(() -> {
                    try { dispose(); } catch (Exception ignored) {}
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

        ActionListener[] listeners = playerSelector.getActionListeners();
        for (var l : listeners) playerSelector.removeActionListener(l);

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

        for (var l : listeners) playerSelector.addActionListener(l);
    }

    private void updateEconomyLabels() {
        if (lblMoney == null || lblPlotId == null) return;

        if (currentPlayerUuid == null) {
            lblMoney.setText("MONEY: $---");
            lblPlotId.setText("PLOT ID: ---");
            return;
        }

        PlayerProfile p = gridManager.getCachedProfile(currentPlayerUuid);
        if (p != null) {
            lblMoney.setText(String.format("MONEY: $%,.2f [%s]", p.getMoney(), p.getRank()));
            lblMoney.setForeground(p.isAdmin() ? new Color(255, 215, 0) : Color.GREEN);

            Long pid = repository.getPlotId(currentPlayerUuid);
            lblPlotId.setText("PLOT ID: #" + (pid != null ? pid : "ERR"));
        }
    }

    private void updateLabels() {
        if (lblTool == null) return;

        String t = factoryPanel.getCurrentToolName();
        if (t.startsWith("STRUCTURE:")) t = "STRUCT (" + t.substring(10) + ")";

        lblTool.setText("TOOL: " + t);
        lblOrient.setText("DIR: " + factoryPanel.getCurrentOrientationName());
        lblLayer.setText("LAYER: " + factoryPanel.getCurrentLayer());
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
            if (currentPlayerUuid != null) gridManager.touchPlayer(currentPlayerUuid);
        });

        btn.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e) && currentPlayerUuid != null) {
                    gridManager.touchPlayer(currentPlayerUuid);
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

    private void setLoading(boolean loading) {
        SwingUtilities.invokeLater(() -> {
            glassPane.setVisible(loading);
            if (playerSelector != null) playerSelector.setEnabled(!loading);
        });
    }

    private void runOffEdt(Runnable r) {
        new Thread(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }, "mw-gui-worker").start();
    }
}
