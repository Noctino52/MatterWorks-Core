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
    private final JPanel inventoryContainer;

    private JComboBox<Object> playerSelector;
    private JLabel lblMoney;
    private JLabel lblTool;
    private JLabel lblOrient;
    private JLabel lblLayer;
    private JLabel lblPlotId;

    private UUID currentPlayerUuid;

    public MatterWorksGUI(GridManager gm, BlockRegistry reg, UUID initialUuid,
                          Runnable onSave, java.util.function.Supplier<Double> moneyProvider,
                          IRepository repo) {

        this.gridManager = gm;
        this.registry = reg;
        this.repository = repo;
        this.currentPlayerUuid = initialUuid;
        this.onSave = onSave;

        this.factoryPanel = new FactoryPanel(gm, reg, currentPlayerUuid, this::updateLabels);
        this.inventoryContainer = new JPanel(new BorderLayout());

        setTitle("MatterWorks Architect - Multi-User Management");
        setSize(1600, 950);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        // --- 1. HEADER: Selezione Player ---
        JPanel headerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 20, 10));
        headerPanel.setBackground(new Color(45, 45, 48));

        playerSelector = new JComboBox<>();
        refreshPlayerList();
        playerSelector.addActionListener(e -> handlePlayerSwitch());

        lblMoney = createLabel("MONEY: $---", Color.GREEN, 16);

        headerPanel.add(new JLabel("ACTIVE USER:") {{ setForeground(Color.WHITE); }});
        headerPanel.add(playerSelector);
        headerPanel.add(lblMoney);

        // --- 2. TOOLBAR: Costruzione ---
        JPanel leftToolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        leftToolbar.setOpaque(false);

        leftToolbar.add(createToolButton("⛏ Drill", "drill_mk1", e -> setTool("drill_mk1")));
        leftToolbar.add(createToolButton("⨠ Belt", "conveyor_belt", e -> setTool("conveyor_belt")));
        leftToolbar.add(createToolButton("🎨 Chromator", "chromator", e -> setTool("chromator")));
        leftToolbar.add(createToolButton("🌀 Mixer", "color_mixer", e -> setTool("color_mixer")));
        leftToolbar.add(createToolButton("🔮 Nexus", "nexus_core", e -> setTool("nexus_core")));

        leftToolbar.add(new JSeparator(SwingConstants.VERTICAL) {{ setPreferredSize(new Dimension(5, 25)); }});
        leftToolbar.add(createSimpleButton("⬇ DOWN", e -> changeLayer(-1)));
        leftToolbar.add(createSimpleButton("⬆ UP", e -> changeLayer(1)));

        // --- 3. SYSTEM BAR: SOS, Save e il ripristinato RESET ---
        JPanel rightSystem = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        rightSystem.setOpaque(false);

        // Bottone SOS (Bailout) [cite: 630]
        JButton btnSOS = createSimpleButton("🆘 SOS", e -> {
            if (gridManager.attemptBailout(currentPlayerUuid)) {
                JOptionPane.showMessageDialog(this, "Richiesta SOS approvata! Saldo ripristinato.");
            } else {
                JOptionPane.showMessageDialog(this, "SOS Negato: Hai asset o fondi a sufficienza.", "Info", JOptionPane.WARNING_MESSAGE);
            }
        });
        btnSOS.setBackground(new Color(220, 150, 0));

        // Bottone SAVE [cite: 628]
        JButton btnSave = createSimpleButton("💾 SAVE", e -> {
            onSave.run();
            JOptionPane.showMessageDialog(this, "Salvataggio completato correttamente.");
        });
        btnSave.setBackground(new Color(0, 100, 200));

        // Bottone RESET (Re-integrato) [cite: 629-633]
        JButton btnReset = createSimpleButton("⚠️ RESET", e -> {
            int choice = JOptionPane.showConfirmDialog(this,
                    "Sei sicuro di voler RESETTARE tutto?\nCancellerà le macchine e rigenererà le vene.\nL'azione è irreversibile.",
                    "Conferma Reset Plot",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.ERROR_MESSAGE);

            if (choice == JOptionPane.YES_OPTION) {
                gridManager.resetUserPlot(currentPlayerUuid); // Riutilizzo della funzione backend [cite: 651-653]
                Timer t = new Timer(600, x -> {
                    factoryPanel.repaint();
                    JOptionPane.showMessageDialog(this, "Plot resettato con successo!");
                });
                t.setRepeats(false);
                t.start();
            }
        });
        btnReset.setBackground(new Color(180, 0, 0));

        rightSystem.add(btnSOS);
        rightSystem.add(btnSave);
        rightSystem.add(btnReset);

        // Assemblaggio Top
        JPanel topContainer = new JPanel(new BorderLayout());
        topContainer.add(headerPanel, BorderLayout.NORTH);

        JPanel midToolbar = new JPanel(new BorderLayout());
        midToolbar.setBackground(new Color(60, 60, 65));
        midToolbar.add(leftToolbar, BorderLayout.WEST);
        midToolbar.add(rightSystem, BorderLayout.EAST);
        topContainer.add(midToolbar, BorderLayout.SOUTH);

        // --- 4. STATUS BAR (Plot ID in basso a destra) ---
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.setBackground(new Color(35, 35, 35));

        JPanel leftStatus = new JPanel(new FlowLayout(FlowLayout.LEFT, 20, 5));
        leftStatus.setOpaque(false);
        lblTool = createLabel("TOOL: Drill", Color.WHITE, 12);
        lblOrient = createLabel("DIR: NORTH", Color.WHITE, 12);
        lblLayer = createLabel("LAYER: 0", Color.CYAN, 12);
        leftStatus.add(lblTool); leftStatus.add(lblOrient); leftStatus.add(lblLayer);

        JPanel rightStatus = new JPanel(new FlowLayout(FlowLayout.RIGHT, 20, 5));
        rightStatus.setOpaque(false);
        lblPlotId = createLabel("PLOT ID: #---", Color.LIGHT_GRAY, 12);
        rightStatus.add(lblPlotId);

        statusPanel.add(leftStatus, BorderLayout.WEST);
        statusPanel.add(rightStatus, BorderLayout.EAST);

        updateInventoryView();

        add(topContainer, BorderLayout.NORTH);
        add(factoryPanel, BorderLayout.CENTER);
        add(inventoryContainer, BorderLayout.EAST);
        add(statusPanel, BorderLayout.SOUTH);

        // Timers aggiornamento UI
        new Timer(50, e -> factoryPanel.repaint()).start();
        new Timer(1000, e -> updateEconomyLabels()).start();

        setVisible(true);
        factoryPanel.requestFocusInWindow();
        updateLabels();
    }

    private void handlePlayerSwitch() {
        Object sel = playerSelector.getSelectedItem();
        if (sel instanceof PlayerProfile p) {
            this.currentPlayerUuid = p.getPlayerId();
            factoryPanel.setPlayerUuid(currentPlayerUuid);
            gridManager.loadPlotFromDB(currentPlayerUuid);
            updateInventoryView();
            updateLabels();
        } else if ("--- ADD NEW PLAYER ---".equals(sel)) {
            String n = JOptionPane.showInputDialog(this, "Nome nuovo giocatore:");
            if (n != null && !n.isBlank()) {
                gridManager.createNewPlayer(n);
                refreshPlayerList();
            }
        }
    }

    private void refreshPlayerList() {
        playerSelector.removeAllItems();
        List<PlayerProfile> all = repository.getAllPlayers();
        for (PlayerProfile p : all) playerSelector.addItem(p);
        playerSelector.addItem("--- ADD NEW PLAYER ---");

        for (int i = 0; i < playerSelector.getItemCount(); i++) {
            if (playerSelector.getItemAt(i) instanceof PlayerProfile p && p.getPlayerId().equals(currentPlayerUuid)) {
                playerSelector.setSelectedIndex(i);
                break;
            }
        }
    }

    private void updateInventoryView() {
        inventoryContainer.removeAll();
        inventoryContainer.add(new InventoryDebugPanel(repository, currentPlayerUuid, gridManager), BorderLayout.CENTER);
        inventoryContainer.revalidate();
        inventoryContainer.repaint();
    }

    private void updateEconomyLabels() {
        PlayerProfile p = repository.loadPlayerProfile(currentPlayerUuid);
        if (p != null) {
            lblMoney.setText(String.format("MONEY: $%,.2f [%s]", p.getMoney(), p.getRank()));
            lblMoney.setForeground(p.isAdmin() ? new Color(255, 215, 0) : Color.GREEN);

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

    private void setTool(String id) { factoryPanel.setTool(id); updateLabels(); }

    private void changeLayer(int delta) {
        int newY = Math.max(0, factoryPanel.getCurrentLayer() + delta);
        factoryPanel.setLayer(newY);
        updateLabels();
    }

    private JButton createToolButton(String text, String itemId, java.awt.event.ActionListener action) {
        JButton btn = createSimpleButton(text, action);
        btn.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
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
        btn.setFont(new Font("SansSerif", Font.PLAIN, 12));
        btn.setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 12));
        return btn;
    }

    private JLabel createLabel(String text, Color color, int size) {
        JLabel lbl = new JLabel(text);
        lbl.setForeground(color);
        lbl.setFont(new Font("Monospaced", Font.BOLD, size));
        return lbl;
    }
}