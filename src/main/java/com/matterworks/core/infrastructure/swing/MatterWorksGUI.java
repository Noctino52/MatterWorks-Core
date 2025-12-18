package com.matterworks.core.infrastructure.swing;

import com.matterworks.core.domain.machines.BlockRegistry;
import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.managers.GridManager;
import com.matterworks.core.ports.IRepository;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.UUID;

public class MatterWorksGUI extends JFrame {

    private final GridManager gridManager;
    private final BlockRegistry registry;
    private final IRepository repository;
    private final Runnable onSave;

    private final FactoryPanel factoryPanel;
    private final JTabbedPane rightTabbedPane;

    private JComboBox<Object> playerSelector;
    private JLabel lblMoney, lblTool, lblOrient, lblLayer, lblPlotId;

    private UUID currentPlayerUuid;

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

        setTitle("MatterWorks Architect - Multi-User Management");
        setSize(1600, 950);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        JPanel topContainer = createTopContainer();
        updateTabs();
        JPanel statusBar = createStatusBar();

        add(topContainer, BorderLayout.NORTH);
        add(factoryPanel, BorderLayout.CENTER);
        add(rightTabbedPane, BorderLayout.EAST);
        add(statusBar, BorderLayout.SOUTH);

        new Timer(50, e -> factoryPanel.repaint()).start();
        new Timer(1000, e -> updateEconomyLabels()).start();

        setVisible(true);
        factoryPanel.requestFocusInWindow();
        updateLabels();
    }

    private JPanel createTopContainer() {
        JPanel container = new JPanel(new BorderLayout());
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 20, 10));
        header.setBackground(new Color(45, 45, 48));

        playerSelector = new JComboBox<>();
        refreshPlayerList();
        playerSelector.addActionListener(e -> handlePlayerSwitch());

        lblMoney = createLabel("MONEY: $---", Color.GREEN, 16);
        header.add(new JLabel("ACTIVE USER:") {{ setForeground(Color.WHITE); }});
        header.add(playerSelector);
        header.add(lblMoney);

        JPanel toolbar = new JPanel(new BorderLayout());
        toolbar.setBackground(new Color(60, 60, 65));

        JPanel leftTools = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        leftTools.setOpaque(false);
        leftTools.add(createToolButton("⛏ Drill", "drill_mk1"));
        leftTools.add(createToolButton("⪠ Belt", "conveyor_belt"));
        leftTools.add(createToolButton("🎨 Chromator", "chromator"));
        leftTools.add(createToolButton("🌪 Mixer", "color_mixer"));
        leftTools.add(createToolButton("🔮 Nexus", "nexus_core"));
        leftTools.add(new JSeparator(SwingConstants.VERTICAL) {{ setPreferredSize(new Dimension(5, 25)); }});
        leftTools.add(createSimpleButton("⬇ DOWN", e -> changeLayer(-1)));
        leftTools.add(createSimpleButton("⬆ UP", e -> changeLayer(1)));

        JPanel rightSystem = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 5));
        rightSystem.setOpaque(false);
        JButton btnSOS = createSimpleButton("🆘 SOS", e -> {
            if (gridManager.attemptBailout(currentPlayerUuid)) {
                JOptionPane.showMessageDialog(this, "SOS approved!");
            }
        });
        btnSOS.setBackground(new Color(220, 150, 0));
        JButton btnSave = createSimpleButton("💾 SAVE", e -> onSave.run());
        btnSave.setBackground(new Color(0, 100, 200));
        JButton btnReset = createSimpleButton("⚠️ RESET", e -> {
            if (JOptionPane.showConfirmDialog(this, "Reset plot?") == JOptionPane.YES_OPTION) {
                gridManager.resetUserPlot(currentPlayerUuid);
            }
        });
        btnReset.setBackground(new Color(180, 0, 0));

        rightSystem.add(btnSOS);
        rightSystem.add(btnSave);
        rightSystem.add(btnReset);

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
        leftStatus.add(lblTool); leftStatus.add(lblOrient); leftStatus.add(lblLayer);
        lblPlotId = createLabel("PLOT ID: #---", Color.LIGHT_GRAY, 12);
        statusPanel.add(leftStatus, BorderLayout.WEST);
        statusPanel.add(lblPlotId, BorderLayout.EAST);
        return statusPanel;
    }

    private void updateTabs() {
        rightTabbedPane.removeAll();
        rightTabbedPane.addTab("Shop", new InventoryDebugPanel(repository, currentPlayerUuid, gridManager));
        rightTabbedPane.addTab("Tech Tree", new TechTreePanel(repository, currentPlayerUuid, gridManager));
    }

    private void handlePlayerSwitch() {
        Object sel = playerSelector.getSelectedItem();
        if (sel instanceof PlayerProfile p) {
            this.currentPlayerUuid = p.getPlayerId();
            factoryPanel.setPlayerUuid(currentPlayerUuid);
            gridManager.loadPlotFromDB(currentPlayerUuid);
            updateTabs();
            updateLabels();
        } else if ("--- ADD NEW PLAYER ---".equals(sel)) {
            String n = JOptionPane.showInputDialog("Name:");
            if (n != null) { gridManager.createNewPlayer(n); refreshPlayerList(); }
        }
    }

    private void refreshPlayerList() {
        playerSelector.removeAllItems();
        List<PlayerProfile> all = repository.getAllPlayers();
        for (PlayerProfile p : all) playerSelector.addItem(p);
        playerSelector.addItem("--- ADD NEW PLAYER ---");
    }

    private void updateEconomyLabels() {
        PlayerProfile p = repository.loadPlayerProfile(currentPlayerUuid);
        if (p != null) {
            lblMoney.setText(String.format("MONEY: $%,.2f [%s]", p.getMoney(), p.getRank()));
            Long pid = repository.getPlotId(currentPlayerUuid);
            lblPlotId.setText("PLOT ID: #" + (pid != null ? pid : "---"));
        }
    }

    private void updateLabels() {
        if (lblTool != null) {
            lblTool.setText("TOOL: " + factoryPanel.getCurrentToolName());
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
        JButton btn = createSimpleButton(text, e -> { factoryPanel.setTool(itemId); updateLabels(); });
        btn.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) gridManager.buyItem(currentPlayerUuid, itemId, 1);
            }
        });
        return btn;
    }

    private JButton createSimpleButton(String text, java.awt.event.ActionListener action) {
        JButton btn = new JButton(text);
        btn.setFocusable(false); btn.addActionListener(action);
        btn.setBackground(new Color(70, 70, 70)); btn.setForeground(Color.WHITE);
        return btn;
    }

    private JLabel createLabel(String text, Color color, int size) {
        JLabel lbl = new JLabel(text);
        lbl.setForeground(color);
        lbl.setFont(new Font("Monospaced", Font.BOLD, size));
        return lbl;
    }
}