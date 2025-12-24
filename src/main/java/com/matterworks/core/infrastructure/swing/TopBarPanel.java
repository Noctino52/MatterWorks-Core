package com.matterworks.core.infrastructure.swing;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class TopBarPanel extends JPanel {

    private final JComboBox<Object> playerSelector = new JComboBox<>();

    private final JLabel moneyLabel = UiKit.label("MONEY: $---", Color.GREEN, 16);
    private final JLabel roleLabel = UiKit.label("[---]", Color.LIGHT_GRAY, 14);
    private final JLabel voidCoinsLabel = UiKit.label("VOID: ---", new Color(190, 0, 220), 14);
    private final JLabel prestigeLabel = UiKit.label("PRESTIGE: ---", new Color(0, 200, 255), 14);

    private final JSpinner layerSpinner;
    private volatile boolean suppressLayerEvents = false;
    private volatile int lastLayerValue = 0;

    // Heights per allineare i controlli (Structure/Y) alle righe bottoni
    private static final int SECTION_LABEL_H = 14;  // altezza label "LOGISTICS"/"PROCESSING"
    private static final int ROW_GAP_H = 2;         // gap tra row1 e rowHeader2 (Box.createVerticalStrut(2) precedente)

    TopBarPanel(
            Consumer<String> onSelectTool,
            BiConsumer<String, Integer> onBuyToolRightClick,
            Consumer<Integer> onLayerDelta,
            Consumer<String> onSelectStructureNativeId,
            Runnable onSOS,
            Runnable onSave,
            Runnable onReset,
            Runnable onDelete
    ) {
        super(new BorderLayout());
        setBackground(new Color(45, 45, 48));

        // ===== HEADER =====
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 8));
        header.setBackground(new Color(45, 45, 48));

        JLabel activeLabel = new JLabel("ACTIVE USER:");
        activeLabel.setForeground(Color.WHITE);

        header.add(activeLabel);
        header.add(playerSelector);
        header.add(moneyLabel);
        header.add(roleLabel);
        header.add(voidCoinsLabel);
        header.add(prestigeLabel);

        // ===== TOOLBAR =====
        JPanel toolbar = new JPanel(new BorderLayout());
        toolbar.setBackground(new Color(60, 60, 65));

        JPanel leftOuter = new JPanel(new BorderLayout(10, 0));
        leftOuter.setOpaque(false);

        // ========== MACHINES: 2 ROWS ==========
        JPanel machines2Rows = new JPanel();
        machines2Rows.setOpaque(false);
        machines2Rows.setLayout(new BoxLayout(machines2Rows, BoxLayout.Y_AXIS));

        JPanel rowHeader1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        rowHeader1.setOpaque(false);
        rowHeader1.setPreferredSize(new Dimension(0, SECTION_LABEL_H));
        rowHeader1.setMaximumSize(new Dimension(Integer.MAX_VALUE, SECTION_LABEL_H));
        rowHeader1.add(sectionLabel("LOGISTICS"));

        JPanel rowHeader2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        rowHeader2.setOpaque(false);
        rowHeader2.setPreferredSize(new Dimension(0, SECTION_LABEL_H));
        rowHeader2.setMaximumSize(new Dimension(Integer.MAX_VALUE, SECTION_LABEL_H));
        rowHeader2.add(sectionLabel("PROCESSING"));

        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        row1.setOpaque(false);

        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        row2.setOpaque(false);

        // ---- ROW 1: logistics + nexus ----
        row1.add(toolButton("⛏ Drill", "drill_mk1", onSelectTool, onBuyToolRightClick));
        row1.add(toolButton("➡ Belt", "conveyor_belt", onSelectTool, onBuyToolRightClick));
        row1.add(vSep());

        row1.add(toolButton("🔀 Split", "splitter", onSelectTool, onBuyToolRightClick));
        row1.add(toolButton("🔗 Merge", "merger", onSelectTool, onBuyToolRightClick));
        row1.add(vSep());

        row1.add(toolButton("⬆ Lift", "lift", onSelectTool, onBuyToolRightClick));
        row1.add(toolButton("⬇ Drop", "dropper", onSelectTool, onBuyToolRightClick));
        row1.add(vSep());

        row1.add(toolButton("🌀 Nexus", "nexus_core", onSelectTool, onBuyToolRightClick));

        // ---- ROW 2: processors + effects ----
        row2.add(toolButton("🎨 Chroma", "chromator", onSelectTool, onBuyToolRightClick));
        row2.add(toolButton("🧪 Mixer", "color_mixer", onSelectTool, onBuyToolRightClick));
        row2.add(vSep());

        row2.add(toolButton("🧽 Shaper", "smoothing", onSelectTool, onBuyToolRightClick));
        row2.add(toolButton("✂ Cutting", "cutting", onSelectTool, onBuyToolRightClick));
        row2.add(vSep());

        row2.add(toolButton("✨ Shiny", "shiny_polisher", onSelectTool, onBuyToolRightClick));
        row2.add(toolButton("🔥 Blaze", "blazing_forge", onSelectTool, onBuyToolRightClick));
        row2.add(toolButton("👾 Glitch", "glitch_distorter", onSelectTool, onBuyToolRightClick));

        machines2Rows.add(rowHeader1);
        machines2Rows.add(row1);
        machines2Rows.add(Box.createVerticalStrut(ROW_GAP_H));
        machines2Rows.add(rowHeader2);
        machines2Rows.add(row2);

        // ========== CONTROLS: allineati alle 2 righe bottoni ==========
        JPanel controls = new JPanel();
        controls.setOpaque(false);
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));

        // Spacer per allinearsi sotto "LOGISTICS"
        controls.add(Box.createVerticalStrut(SECTION_LABEL_H));

        JButton btnStructure = UiKit.button("🧱 Structure", e -> {
            String blockId = JOptionPane.showInputDialog(
                    this,
                    "Enter Native Block ID (e.g., hytale:stone):",
                    "hytale:stone"
            );
            if (blockId != null && !blockId.isBlank()) onSelectStructureNativeId.accept(blockId);
        });
        btnStructure.setBackground(new Color(100, 100, 100));
        btnStructure.setAlignmentX(Component.LEFT_ALIGNMENT);

        controls.add(btnStructure);

        // Spacer per “gap + header2”
        controls.add(Box.createVerticalStrut(ROW_GAP_H + SECTION_LABEL_H));

        JPanel yPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        yPanel.setOpaque(false);
        yPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel yLabel = UiKit.label("Y:", Color.CYAN, 14);

        SpinnerNumberModel model = new SpinnerNumberModel(0, 0, 255, 1);
        layerSpinner = new JSpinner(model);
        layerSpinner.setPreferredSize(new Dimension(74, 26));

        JComponent editor = layerSpinner.getEditor();
        if (editor instanceof JSpinner.DefaultEditor de) {
            de.getTextField().setColumns(4);
            de.getTextField().setHorizontalAlignment(SwingConstants.RIGHT);
        }

        layerSpinner.addChangeListener(new ChangeListener() {
            @Override public void stateChanged(ChangeEvent e) {
                if (suppressLayerEvents) return;
                int newVal = (layerSpinner.getValue() instanceof Integer i) ? i : 0;

                int delta = newVal - lastLayerValue;
                if (delta == 0) return;

                lastLayerValue = newVal;
                onLayerDelta.accept(delta);
            }
        });

        yPanel.add(yLabel);
        yPanel.add(layerSpinner);
        controls.add(yPanel);

        leftOuter.add(machines2Rows, BorderLayout.CENTER);
        leftOuter.add(controls, BorderLayout.EAST);

        // ========== RIGHT SYSTEM BUTTONS ==========
        JPanel rightSystem = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 8));
        rightSystem.setOpaque(false);

        JButton btnSOS = UiKit.button("🆘 SOS", e -> onSOS.run());
        btnSOS.setBackground(new Color(220, 150, 0));

        JButton btnSave = UiKit.button("💾 SAVE", e -> onSave.run());
        btnSave.setBackground(new Color(0, 100, 200));

        JButton btnReset = UiKit.button("♻ RESET", e -> onReset.run());
        btnReset.setBackground(new Color(180, 0, 0));

        JButton btnDelete = UiKit.button("🗑 DELETE", e -> onDelete.run());
        btnDelete.setBackground(Color.BLACK);
        btnDelete.setForeground(Color.RED);
        btnDelete.setBorder(BorderFactory.createLineBorder(Color.RED, 1));

        rightSystem.add(btnSOS);
        rightSystem.add(btnSave);
        rightSystem.add(btnReset);
        rightSystem.add(btnDelete);

        toolbar.add(leftOuter, BorderLayout.CENTER);
        toolbar.add(rightSystem, BorderLayout.EAST);

        add(header, BorderLayout.NORTH);
        add(toolbar, BorderLayout.CENTER);
    }

    JComboBox<Object> getPlayerSelector() { return playerSelector; }
    JLabel getMoneyLabel() { return moneyLabel; }
    JLabel getRoleLabel() { return roleLabel; }
    JLabel getVoidCoinsLabel() { return voidCoinsLabel; }
    JLabel getPrestigeLabel() { return prestigeLabel; }

    void setLayerValue(int y) {
        int val = Math.max(0, Math.min(255, y));
        if (val == lastLayerValue) return;

        suppressLayerEvents = true;
        try {
            lastLayerValue = val;
            layerSpinner.setValue(val);
        } finally {
            suppressLayerEvents = false;
        }
    }

    private JLabel sectionLabel(String text) {
        JLabel l = UiKit.label(text, new Color(180, 180, 180), 11);
        l.setFont(new Font("SansSerif", Font.BOLD, 11));
        return l;
    }

    private JComponent vSep() {
        JSeparator sep = new JSeparator(SwingConstants.VERTICAL);
        sep.setForeground(new Color(120, 120, 120));
        sep.setPreferredSize(new Dimension(10, 24));
        return sep;
    }

    private JButton toolButton(String text,
                               String itemId,
                               Consumer<String> onSelectTool,
                               BiConsumer<String, Integer> onBuyToolRightClick) {

        JButton btn = UiKit.button(text, e -> onSelectTool.accept(itemId));
        btn.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    onBuyToolRightClick.accept(itemId, 1);
                }
            }
        });
        return btn;
    }
}
