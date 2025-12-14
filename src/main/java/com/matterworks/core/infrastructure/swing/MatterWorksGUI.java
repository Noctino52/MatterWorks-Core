package com.matterworks.core.infrastructure.swing;

import com.matterworks.core.domain.machines.BlockRegistry;
import com.matterworks.core.managers.GridManager;

import javax.swing.*;
import java.awt.*;
import java.util.UUID;
import java.util.function.Supplier;

public class MatterWorksGUI extends JFrame {

    private final FactoryPanel panel;

    // Etichette Info
    private final JLabel lblTool;
    private final JLabel lblOrient;
    private final JLabel lblLayer;

    // Etichetta Soldi
    private final JLabel lblMoney;

    public MatterWorksGUI(GridManager gridManager, BlockRegistry registry, UUID playerUuid, Runnable onSave, Supplier<Double> moneyProvider) {
        setTitle("MatterWorks Architect (Java 25)");
        setSize(1280, 900);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        setLayout(new BorderLayout());

        // --- 1. INIZIALIZZAZIONE COMPONENTI INFO (Prima di tutto!) ---
        // Devono esistere perché il Panel proverà ad aggiornarle
        lblTool = createLabel("TOOL: Drill");
        lblOrient = createLabel("DIR: NORTH");
        lblLayer = createLabel("LAYER Y: 64");
        lblLayer.setForeground(Color.CYAN);

        lblMoney = new JLabel("MONEY: $---");
        lblMoney.setFont(new Font("Monospaced", Font.BOLD, 16));
        lblMoney.setForeground(Color.GREEN);

        // --- 2. INIZIALIZZAZIONE GRIGLIA (PANEL) ---
        // Ora possiamo crearlo perché le Label esistono
        panel = new FactoryPanel(gridManager, registry, playerUuid, this::updateLabels);

        // --- 3. CREAZIONE PULSANTI (Ora 'panel' esiste!) ---

        // A. Pannello Sinistro (Strumenti)
        JPanel leftTools = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        leftTools.setOpaque(false);

        JButton btnDrill = createButton("⛏ Drill", e -> setTool("drill_mk1"));
        JButton btnBelt = createButton("⨠ Belt", e -> setTool("conveyor_belt"));
        JButton btnNexus = createButton("🔮 Nexus", e -> setTool("nexus_core"));

        JSeparator sep1 = new JSeparator(SwingConstants.VERTICAL); sep1.setPreferredSize(new Dimension(5, 25));

        JButton btnLayerUp = createButton("⬆ Layer UP", e -> changeLayer(1));
        JButton btnLayerDown = createButton("⬇ Layer DOWN", e -> changeLayer(-1));

        JSeparator sep2 = new JSeparator(SwingConstants.VERTICAL); sep2.setPreferredSize(new Dimension(5, 25));

        // Qui dava errore prima: ora panel è inizializzato!
        JButton btnRotate = createButton("↻ Rotate (R)", e -> panel.rotate());

        leftTools.add(btnDrill);
        leftTools.add(btnBelt);
        leftTools.add(btnNexus);
        leftTools.add(sep1);
        leftTools.add(btnLayerDown);
        leftTools.add(btnLayerUp);
        leftTools.add(sep2);
        leftTools.add(btnRotate);

        // B. Pannello Destro (Sistema)
        JPanel rightSystem = new JPanel(new FlowLayout(FlowLayout.RIGHT, 15, 0));
        rightSystem.setOpaque(false);

        JButton btnSave = createButton("💾 SAVE", e -> {
            onSave.run();
            JOptionPane.showMessageDialog(this, "Salvataggio Completato!", "Sistema", JOptionPane.INFORMATION_MESSAGE);
        });
        btnSave.setBackground(new Color(0, 100, 200));

        rightSystem.add(lblMoney);
        rightSystem.add(btnSave);

        // --- 4. ASSEMBLAGGIO LAYOUT ---

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

        // --- 5. TIMERS ---
        new Timer(50, e -> panel.repaint()).start();

        new Timer(1000, e -> {
            double m = moneyProvider.get();
            lblMoney.setText(String.format("MONEY: $%,.2f", m));
        }).start();

        setVisible(true);
        panel.requestFocusInWindow();

        // Aggiorna le label iniziali con i valori del panel
        updateLabels();
    }

    // --- AZIONI ---
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
        // Controllo di sicurezza se chiamato dal costruttore del panel prima dell'init
        if (lblTool != null && panel != null) {
            lblTool.setText("TOOL: " + panel.getCurrentToolName());
            lblOrient.setText("DIR: " + panel.getCurrentOrientationName());
            lblLayer.setText("LAYER Y: " + panel.getCurrentLayer());
        }
    }

    // --- HELPER UI ---
    private JButton createButton(String text, java.awt.event.ActionListener action) {
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