package com.matterworks.core.ui.swing.panels;

import com.matterworks.core.ui.swing.kit.UiKit;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class TopBarPanel extends JPanel {

    private final JComboBox<Object> playerSelector = new JComboBox<>();

    private final JLabel moneyLabel = UiKit.label("MONEY: $---", Color.GREEN, 16);
    private final JLabel roleLabel = UiKit.label("[---]", Color.LIGHT_GRAY, 14);
    private final JLabel voidCoinsLabel = UiKit.label("VOID: ---", new Color(190, 0, 220), 14);
    private final JLabel prestigeLabel = UiKit.label("PRESTIGE: ---", new Color(0, 200, 255), 14);

    // System buttons
    private final JButton btnPrestigeClassic;
    private final JButton btnPrestigeInstant;


    private final JSpinner layerSpinner;
    private volatile boolean suppressLayerEvents = false;
    private volatile int lastLayerValue = 0;

    private static final int SECTION_LABEL_H = 14;
    private static final int ROW_GAP_H = 2;

    /**
     * ✅ Backward compatibility: original constructor (used by older MatterWorksGUI versions).
     * Instant + Booster are present but no-op.
     */
    public TopBarPanel(
            Consumer<String> onSelectTool,
            BiConsumer<String, Integer> onBuyToolRightClick,
            Consumer<Integer> onLayerDelta,
            Consumer<String> onSelectStructureNativeId,
            Runnable onSOS,
            Runnable onSave,
            Runnable onReset,
            Runnable onPrestigeClassic,
            Runnable onDelete
    ) {
        this(
                onSelectTool,
                onBuyToolRightClick,
                onLayerDelta,
                onSelectStructureNativeId,
                onSOS,
                onSave,
                onReset,
                onPrestigeClassic,
                () -> {},     // instant prestige no-op
                () -> {},     // booster no-op
                onDelete
        );
    }

    /**
     * ✅ Compatibility: Instant supported, Booster no-op.
     */
    public TopBarPanel(
            Consumer<String> onSelectTool,
            BiConsumer<String, Integer> onBuyToolRightClick,
            Consumer<Integer> onLayerDelta,
            Consumer<String> onSelectStructureNativeId,
            Runnable onSOS,
            Runnable onSave,
            Runnable onReset,
            Runnable onPrestigeClassic,
            Runnable onPrestigeInstant,
            Runnable onDelete
    ) {
        this(
                onSelectTool,
                onBuyToolRightClick,
                onLayerDelta,
                onSelectStructureNativeId,
                onSOS,
                onSave,
                onReset,
                onPrestigeClassic,
                onPrestigeInstant,
                () -> {},     // booster no-op
                onDelete
        );
    }

    /**
     * ✅ Full constructor: Classic + Instant + Booster.
     */
    public TopBarPanel(
            Consumer<String> onSelectTool,
            BiConsumer<String, Integer> onBuyToolRightClick,
            Consumer<Integer> onLayerDelta,
            Consumer<String> onSelectStructureNativeId,
            Runnable onSOS,
            Runnable onSave,
            Runnable onReset,
            Runnable onPrestigeClassic,
            Runnable onPrestigeInstant,
            Runnable onBooster,
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

        // ===== MACHINES (2 ROWS) =====
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

        row1.add(toolButton("⛏ Drill", "drill", onSelectTool, onBuyToolRightClick));
        row1.add(toolButton("➡ Belt", "conveyor_belt", onSelectTool, onBuyToolRightClick));
        row1.add(vSep());

        row1.add(toolButton("🔀 Split", "splitter", onSelectTool, onBuyToolRightClick));
        row1.add(toolButton("🔁 Merge", "merger", onSelectTool, onBuyToolRightClick));
        row1.add(vSep());

        row1.add(toolButton("⬆ Lift", "lift", onSelectTool, onBuyToolRightClick));
        row1.add(toolButton("⬇ Drop", "dropper", onSelectTool, onBuyToolRightClick));
        row1.add(vSep());

        // Nexus: selectable, buy disabled (right click null)
        row1.add(toolButton("🌌 Nexus", "nexus_core", onSelectTool, null));

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

        // ===== CONTROLS (Structure + Booster + Y Layer) =====
        JPanel controls = new JPanel();
        controls.setOpaque(false);
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));

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

        // ✅ NEW: Booster button (next to Structure area)
        controls.add(Box.createVerticalStrut(6));
        JButton btnBooster = UiKit.button("🚀 Booster", e -> onBooster.run());
        btnBooster.setBackground(new Color(0, 120, 90));
        btnBooster.setAlignmentX(Component.LEFT_ALIGNMENT);
        controls.add(btnBooster);

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

        // ===== RIGHT SYSTEM BUTTONS =====
        JPanel rightSystem = new JPanel(new GridBagLayout());
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

        // Classic prestige
        btnPrestigeClassic = UiKit.button("🟣 PRESTIGE", e -> onPrestigeClassic.run());
        btnPrestigeClassic.setBackground(new Color(150, 0, 200));
        btnPrestigeClassic.setEnabled(false);
        btnPrestigeClassic.setToolTipText("Finish the Tech Tree to unlock Prestige.");

        // Instant prestige
        btnPrestigeInstant = UiKit.button("✨ INSTANT", e -> onPrestigeInstant.run());
        btnPrestigeInstant.setBackground(new Color(120, 40, 180));
        btnPrestigeInstant.setEnabled(false);
        btnPrestigeInstant.setToolTipText("Requires: instant_prestige (Void Shop).");
        btnPrestigeInstant.setVisible(true);

        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.insets = new Insets(0, 6, 0, 6);
        c.anchor = GridBagConstraints.EAST;

        c.gridx = 0; rightSystem.add(btnSOS, c);
        c.gridx = 1; rightSystem.add(btnSave, c);
        c.gridx = 2; rightSystem.add(btnReset, c);
        c.gridx = 3; rightSystem.add(btnDelete, c);

        c.gridy = 1;
        c.insets = new Insets(4, 6, 0, 6);

        c.gridx = 2;
        rightSystem.add(btnPrestigeInstant, c);

        c.gridx = 3;
        rightSystem.add(btnPrestigeClassic, c);

        toolbar.add(leftOuter, BorderLayout.CENTER);
        toolbar.add(rightSystem, BorderLayout.EAST);

        add(header, BorderLayout.NORTH);
        add(toolbar, BorderLayout.CENTER);
    }

    // ===== GETTERS used by MatterWorksGUI =====
    public JComboBox<Object> getPlayerSelector() { return playerSelector; }
    public JLabel getMoneyLabel() { return moneyLabel; }
    public JLabel getRoleLabel() { return roleLabel; }
    public JLabel getVoidCoinsLabel() { return voidCoinsLabel; }
    public JLabel getPrestigeLabel() { return prestigeLabel; }

    // ===== Prestige buttons control =====
    public void setPrestigeButtonEnabled(boolean enabled) {
        btnPrestigeClassic.setEnabled(enabled);
    }

    public void setPrestigeButtonToolTip(String tooltip) {
        btnPrestigeClassic.setToolTipText(tooltip);
    }

    public void setInstantPrestigeButtonEnabled(boolean enabled) {
        btnPrestigeInstant.setEnabled(enabled);
    }

    public void setInstantPrestigeButtonVisible(boolean visible) {
        btnPrestigeInstant.setVisible(visible);
    }

    public void setInstantPrestigeButtonToolTip(String tooltip) {
        btnPrestigeInstant.setToolTipText(tooltip);
    }

    // ===== Layer control =====
    public void setLayerValue(int y) {
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


    // ===== UI helpers =====
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

    private JButton toolButton(
            String text,
            String itemId,
            Consumer<String> onSelectTool,
            BiConsumer<String, Integer> onBuyToolRightClick
    ) {
        JButton btn = UiKit.button(text, e -> onSelectTool.accept(itemId));
        btn.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e) && onBuyToolRightClick != null) {
                    onBuyToolRightClick.accept(itemId, 1);
                }
            }
        });
        return btn;
    }
}
