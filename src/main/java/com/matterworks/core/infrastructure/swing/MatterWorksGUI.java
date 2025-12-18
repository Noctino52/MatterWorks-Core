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
    private JLabel lblMoney;

    // Status Labels
    private JLabel lblTool;
    private JLabel lblOrient;
    private JLabel lblLayer;
    private JLabel lblPlotId;

    private UUID currentPlayerUuid;

    public MatterWorksGUI(GridManager gm, BlockRegistry reg, UUID initialUuid,
                          Runnable onSave, IRepository repo) {

        this.gridManager = gm;
        this.registry = reg;
        this.repository = repo;
        this.currentPlayerUuid = initialUuid;
        this.onSave = onSave;

        // Inizializzazione Pannelli
        this.factoryPanel = new FactoryPanel(gm, reg, currentPlayerUuid, this::updateLabels);
        this.rightTabbedPane = new JTabbedPane();
        this.rightTabbedPane.setPreferredSize(new Dimension(340, 0)); // Larghezza fissa colonna destra

        // Configurazione Finestra
        setTitle("MatterWorks Architect - Multi-User Management");
        setSize(1600, 950);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        // --- 1. COSTRUZIONE UI ---
        JPanel topContainer = createTopContainer();
        updateTabs(); // Inizializza i tab Inventario e Tech Tree
        JPanel statusBar = createStatusBar();

        // --- 2. ASSEMBLAGGIO ---
        add(topContainer, BorderLayout.NORTH);
        add(factoryPanel, BorderLayout.CENTER);
        add(rightTabbedPane, BorderLayout.EAST);
        add(statusBar, BorderLayout.SOUTH);

        // --- 3. TIMERS ---
        // Refresh rendering (50ms -> 20 FPS)
        new Timer(50, e -> factoryPanel.repaint()).start();
        // Refresh economia e status (1s)
        new Timer(1000, e -> updateEconomyLabels()).start();

        setVisible(true);
        factoryPanel.requestFocusInWindow();
        updateLabels(); // Prima sync delle label
    }

    private JPanel createTopContainer() {
        JPanel container = new JPanel(new BorderLayout());

        // A. HEADER (Selezione Player e Soldi)
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 20, 10));
        header.setBackground(new Color(45, 45, 48));

        playerSelector = new JComboBox<>();
        refreshPlayerList();
        playerSelector.addActionListener(e -> handlePlayerSwitch());

        lblMoney = createLabel("MONEY: $---", Color.GREEN, 16);

        header.add(new JLabel("ACTIVE USER:") {{ setForeground(Color.WHITE); }});
        header.add(playerSelector);
        header.add(lblMoney);

        // B. TOOLBAR (Strumenti, Layer, System)
        JPanel toolbar = new JPanel(new BorderLayout());
        toolbar.setBackground(new Color(60, 60, 65));

        // B1. Sinistra: Tools di Costruzione
        JPanel leftTools = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        leftTools.setOpaque(false);

        // Bottoni Tool (Sempre cliccabili per mostrare il fantasma, la logica blocca dopo)
        leftTools.add(createToolButton("⛏️ Drill", "drill_mk1"));
        leftTools.add(createToolButton("⪠ Belt", "conveyor_belt"));
        leftTools.add(createToolButton("🎨 Chromator", "chromator"));   // Richiesto
        leftTools.add(createToolButton("🌪️ Mixer", "color_mixer"));   // Richiesto
        leftTools.add(createToolButton("🔮 Nexus", "nexus_core"));

        // Separatore
        leftTools.add(new JSeparator(SwingConstants.VERTICAL) {{ setPreferredSize(new Dimension(5, 25)); }});

        // Controlli Layer (Ripristinati)
        leftTools.add(createSimpleButton("⬇ DOWN", e -> changeLayer(-1)));
        leftTools.add(createSimpleButton("⬆ UP", e -> changeLayer(1)));

        // B2. Destra: System Buttons (SOS, Save, Reset)
        JPanel rightSystem = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 5));
        rightSystem.setOpaque(false);

        // SOS Button
        JButton btnSOS = createSimpleButton("🆘 SOS", e -> {
            if (gridManager.attemptBailout(currentPlayerUuid)) {
                JOptionPane.showMessageDialog(this, "Richiesta SOS approvata! Saldo ripristinato.");
            } else {
                JOptionPane.showMessageDialog(this, "SOS Negato: Hai asset o fondi a sufficienza.", "Info", JOptionPane.WARNING_MESSAGE);
            }
        });
        btnSOS.setBackground(new Color(220, 150, 0));

        // Save Button
        JButton btnSave = createSimpleButton("💾 SAVE", e -> {
            onSave.run();
            JOptionPane.showMessageDialog(this, "Salvataggio completato correttamente.");
        });
        btnSave.setBackground(new Color(0, 100, 200));

        // Reset Button
        JButton btnReset = createSimpleButton("⚠️ RESET", e -> {
            int choice = JOptionPane.showConfirmDialog(this,
                    "Sei sicuro di voler RESETTARE tutto?\nCancellerà le macchine e rigenererà le vene.\nL'azione è irreversibile.",
                    "Conferma Reset Plot",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.ERROR_MESSAGE);

            if (choice == JOptionPane.YES_OPTION) {
                gridManager.resetUserPlot(currentPlayerUuid);
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

        toolbar.add(leftTools, BorderLayout.WEST);
        toolbar.add(rightSystem, BorderLayout.EAST);

        container.add(header, BorderLayout.NORTH);
        container.add(toolbar, BorderLayout.SOUTH);
        return container;
    }

    private JPanel createStatusBar() {
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.setBackground(new Color(35, 35, 35));
        statusPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));

        // Info Sinistra: Tool, Orientamento, Layer
        JPanel leftStatus = new JPanel(new FlowLayout(FlowLayout.LEFT, 20, 0));
        leftStatus.setOpaque(false);

        lblTool = createLabel("TOOL: Drill", Color.WHITE, 12);
        lblOrient = createLabel("DIR: NORTH", Color.WHITE, 12);
        lblLayer = createLabel("LAYER: 0", Color.CYAN, 12); // Ripristinato Layer Label

        leftStatus.add(lblTool);
        leftStatus.add(lblOrient);
        leftStatus.add(lblLayer);

        // Info Destra: Plot ID
        lblPlotId = createLabel("PLOT ID: #---", Color.LIGHT_GRAY, 12);

        statusPanel.add(leftStatus, BorderLayout.WEST);
        statusPanel.add(lblPlotId, BorderLayout.EAST);

        return statusPanel;
    }

    // --- LOGICA GESTIONE TAB & PLAYER ---

    private void updateTabs() {
        // Ricostruisce i pannelli laterali quando cambia il player o all'avvio
        rightTabbedPane.removeAll();
        rightTabbedPane.addTab("Warehouse Shop", new InventoryDebugPanel(repository, currentPlayerUuid, gridManager));
        rightTabbedPane.addTab("Tech Tree", new TechTreePanel(repository, currentPlayerUuid, gridManager));
    }

    private void handlePlayerSwitch() {
        Object sel = playerSelector.getSelectedItem();
        if (sel instanceof PlayerProfile p) {
            this.currentPlayerUuid = p.getPlayerId();
            factoryPanel.setPlayerUuid(currentPlayerUuid);
            gridManager.loadPlotFromDB(currentPlayerUuid);

            // Aggiorna tutto il contesto
            updateTabs();
            updateEconomyLabels();
            updateLabels();
        } else if ("--- ADD NEW PLAYER ---".equals(sel)) {
            String n = JOptionPane.showInputDialog(this, "Nome nuovo giocatore:");
            if (n != null && !n.isBlank()) {
                gridManager.createNewPlayer(n); // Questo metodo deve esistere in GridManager (lo avevamo aggiunto nei codici precedenti)
                refreshPlayerList();
            }
        }
    }

    private void refreshPlayerList() {
        playerSelector.removeAllItems();
        List<PlayerProfile> all = repository.getAllPlayers();
        for (PlayerProfile p : all) playerSelector.addItem(p);
        playerSelector.addItem("--- ADD NEW PLAYER ---");

        // Ripristina selezione corrente
        for (int i = 0; i < playerSelector.getItemCount(); i++) {
            if (playerSelector.getItemAt(i) instanceof PlayerProfile p && p.getPlayerId().equals(currentPlayerUuid)) {
                playerSelector.setSelectedIndex(i);
                break;
            }
        }
    }

    // --- LOGICA UPDATE UI ---

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
        // Callback chiamata da FactoryPanel quando cambia stato (es. rotazione 'R') o cambio layer
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

    // --- FACTORY METHODS PER COMPONENTI SWING ---

    private JButton createToolButton(String text, String itemId) {
        JButton btn = createSimpleButton(text, e -> {
            factoryPanel.setTool(itemId); // Imposta il fantasma visivo
            updateLabels();
        });

        // Aggiunge click destro per acquisto rapido (opzionale, ma utile)
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