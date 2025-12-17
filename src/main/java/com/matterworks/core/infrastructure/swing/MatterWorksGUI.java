package com.matterworks.core.infrastructure.swing;

import com.matterworks.core.domain.machines.BlockRegistry;
import com.matterworks.core.managers.GridManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.UUID;
import java.util.function.Supplier;

public class MatterWorksGUI extends JFrame {

    private final FactoryPanel panel;
    private final JLabel lblTool;
    private final JLabel lblOrient;
    private final JLabel lblLayer;
    private final JLabel lblMoney;
    private final GridManager gridManager;
    private final UUID playerUuid;

    public MatterWorksGUI(GridManager gridManager, BlockRegistry registry, UUID playerUuid, Runnable onSave, Supplier<Double> moneyProvider) {
        this.gridManager = gridManager;
        this.playerUuid = playerUuid;

        setTitle("MatterWorks Architect (Java 25)");
        setSize(1280, 900);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        // INFO LABELS
        lblTool = createLabel("TOOL: Drill");
        lblOrient = createLabel("DIR: NORTH");
        lblLayer = createLabel("LAYER Y: 0");
        lblLayer.setForeground(Color.CYAN);

        lblMoney = new JLabel("MONEY: $---");
        lblMoney.setFont(new Font("Monospaced", Font.BOLD, 16));
        lblMoney.setForeground(Color.GREEN);

        // GRID PANEL
        panel = new FactoryPanel(gridManager, registry, playerUuid, this::updateLabels);

        // --- LEFT TOOLBAR ---
        JPanel leftTools = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        leftTools.setOpaque(false);

        // Bottoni con ID item per acquisto rapido
        JButton btnDrill = createButton("⛏ Drill", "drill_mk1", e -> setTool("drill_mk1"));
        JButton btnBelt = createButton("⨠ Belt", "conveyor_belt", e -> setTool("conveyor_belt"));

        JButton btnChromator = createButton("🎨 Chromator", "chromator", e -> setTool("chromator"));
        btnChromator.setBackground(new Color(255, 140, 0));
        btnChromator.setForeground(Color.BLACK);

        JButton btnMixer = createButton("🌀 Mixer", "color_mixer", e -> setTool("color_mixer"));
        btnMixer.setBackground(new Color(0, 200, 200));
        btnMixer.setForeground(Color.BLACK);

        JButton btnNexus = createButton("🔮 Nexus", "nexus_core", e -> setTool("nexus_core"));
        btnNexus.setBackground(new Color(100, 0, 150));
        btnNexus.setForeground(Color.WHITE);

        JSeparator sep1 = new JSeparator(SwingConstants.VERTICAL); sep1.setPreferredSize(new Dimension(5, 25));
        JButton btnLayerUp = createButtonSimple("⬆ Layer UP", e -> changeLayer(1));
        JButton btnLayerDown = createButtonSimple("⬇ Layer DOWN", e -> changeLayer(-1));
        JSeparator sep2 = new JSeparator(SwingConstants.VERTICAL); sep2.setPreferredSize(new Dimension(5, 25));
        JButton btnRotate = createButtonSimple("↻ Rotate (R)", e -> panel.rotate());

        leftTools.add(btnDrill);
        leftTools.add(btnBelt);
        leftTools.add(btnChromator);
        leftTools.add(btnMixer);
        leftTools.add(btnNexus);
        leftTools.add(sep1);
        leftTools.add(btnLayerDown);
        leftTools.add(btnLayerUp);
        leftTools.add(sep2);
        leftTools.add(btnRotate);

        // --- RIGHT SYSTEM BUTTONS ---
        JPanel rightSystem = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        rightSystem.setOpaque(false);

        JButton btnSave = createButtonSimple("💾 SAVE", e -> {
            onSave.run();
            JOptionPane.showMessageDialog(this, "Salvataggio Completato!", "Sistema", JOptionPane.INFORMATION_MESSAGE);
        });
        btnSave.setBackground(new Color(0, 100, 200));

        JButton btnBailout = createButtonSimple("🆘 SOS", e -> {
            boolean success = gridManager.attemptBailout(playerUuid);
            if (success) JOptionPane.showMessageDialog(this, "Richiesta approvata! +500$ accreditati.", "MatterWorks Bailout", JOptionPane.INFORMATION_MESSAGE);
            else JOptionPane.showMessageDialog(this, "Richiesta negata.\nHai già asset sufficienti.", "MatterWorks Bailout", JOptionPane.ERROR_MESSAGE);
        });
        btnBailout.setBackground(new Color(220, 150, 0));
        btnBailout.setForeground(Color.BLACK);

        JButton btnReset = createButtonSimple("⚠️ RESET", e -> {
            int choice = JOptionPane.showConfirmDialog(this,
                    "Sei sicuro di voler RESETTARE tutto?\nCancellerà tutte le macchine e cambierà posizione alle vene.\nNon si può annullare.",
                    "Conferma Reset", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

            if (choice == JOptionPane.YES_OPTION) {
                gridManager.resetUserPlot(playerUuid);
                Timer t = new Timer(500, x -> {
                    panel.repaint();
                    JOptionPane.showMessageDialog(this, "Plot resettato e vene rigenerate!", "Reset", JOptionPane.INFORMATION_MESSAGE);
                });
                t.setRepeats(false);
                t.start();
            }
        });
        btnReset.setBackground(new Color(200, 0, 0));

        rightSystem.add(lblMoney);
        rightSystem.add(Box.createHorizontalStrut(10));
        rightSystem.add(btnBailout);
        rightSystem.add(btnSave);
        rightSystem.add(btnReset);

        // LAYOUT
        JPanel topContainer = new JPanel(new BorderLayout());
        topContainer.setBackground(new Color(45, 45, 48));
        topContainer.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        topContainer.add(leftTools, BorderLayout.WEST);
        topContainer.add(rightSystem, BorderLayout.EAST);

        JPanel infoPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 20, 5));
        infoPanel.setBackground(new Color(60, 60, 65));
        infoPanel.add(lblTool);
        infoPanel.add(lblOrient);
        infoPanel.add(lblLayer);

        JPanel northGroup = new JPanel(new BorderLayout());
        northGroup.add(topContainer, BorderLayout.NORTH);
        northGroup.add(infoPanel, BorderLayout.SOUTH);

        add(northGroup, BorderLayout.NORTH);
        add(panel, BorderLayout.CENTER);

        // TIMERS
        new Timer(50, e -> panel.repaint()).start();
        new Timer(1000, e -> {
            double m = moneyProvider.get();
            lblMoney.setText(String.format("MONEY: $%,.2f", m));
        }).start();

        setVisible(true);
        panel.requestFocusInWindow();
        updateLabels();
    }

    private void setTool(String toolId) {
        panel.setTool(toolId);
        updateLabels();
    }

    private void changeLayer(int delta) {
        int newY = panel.getCurrentLayer() + delta;
        if (newY < 0) newY = 0;
        panel.setLayer(newY);
        updateLabels();
    }

    private void updateLabels() {
        if (lblTool != null && panel != null) {
            lblTool.setText("TOOL: " + panel.getCurrentToolName());
            lblOrient.setText("DIR: " + panel.getCurrentOrientationName());
            lblLayer.setText("LAYER Y: " + panel.getCurrentLayer());
        }
    }

    // Bottone "Intelligente" con Click Destro per Shop
    private JButton createButton(String text, String itemId, java.awt.event.ActionListener setToolAction) {
        JButton btn = new JButton(text);
        btn.setFocusable(false);

        // Click Sinistro: Seleziona Tool
        btn.addActionListener(setToolAction);

        // Click Destro: Compra 1 unità
        btn.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    boolean bought = gridManager.buyItem(playerUuid, itemId, 1);
                    if (bought) {
                        // Feedback visivo temporaneo
                        Color original = btn.getBackground();
                        btn.setBackground(Color.GREEN);
                        Timer t = new Timer(200, x -> btn.setBackground(original));
                        t.setRepeats(false); t.start();
                    } else {
                        Toolkit.getDefaultToolkit().beep();
                        JOptionPane.showMessageDialog(btn, "Soldi insufficienti per comprare " + itemId, "Shop", JOptionPane.ERROR_MESSAGE);
                    }
                }
            }
        });

        btn.setBackground(new Color(70, 70, 70));
        btn.setForeground(Color.WHITE);
        btn.setFont(new Font("SansSerif", Font.PLAIN, 12));
        btn.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        // Tooltip per spiegare i controlli
        btn.setToolTipText("SX: Seleziona | DX: Compra 1x");
        return btn;
    }

    // Bottone Semplice per azioni senza item (Reset, Save, Layer)
    private JButton createButtonSimple(String text, java.awt.event.ActionListener action) {
        JButton btn = new JButton(text);
        btn.setFocusable(false);
        btn.addActionListener(action);
        btn.setBackground(new Color(70, 70, 70));
        btn.setForeground(Color.WHITE);
        btn.setFont(new Font("SansSerif", Font.PLAIN, 12));
        btn.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        return btn;
    }

    private JLabel createLabel(String text) {
        JLabel lbl = new JLabel(text);
        lbl.setForeground(Color.WHITE);
        lbl.setFont(new Font("Monospaced", Font.BOLD, 14));
        return lbl;
    }
}