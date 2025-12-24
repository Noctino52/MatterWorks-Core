// FILE: src/main/java/com/matterworks/core/infrastructure/swing/TopBarPanel.java
package com.matterworks.core.infrastructure.swing;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class TopBarPanel extends JPanel {

    private final JComboBox<Object> playerSelector = new JComboBox<>();
    private final JLabel moneyLabel = UiKit.label("MONEY: $---", Color.GREEN, 16);

    public TopBarPanel(Consumer<String> onSelectTool,
                       BiConsumer<String, Integer> onBuyToolRightClick,
                       Consumer<Integer> onLayerDelta,
                       Consumer<String> onSelectStructureNativeId,
                       Runnable onSOS,
                       Runnable onSave,
                       Runnable onReset,
                       Runnable onDelete) {

        setLayout(new BorderLayout());
        setBackground(new Color(45, 45, 48));

        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 20, 10));
        header.setOpaque(false);

        JLabel activeLabel = UiKit.label("ACTIVE USER:", Color.WHITE, 14);
        header.add(activeLabel);
        header.add(playerSelector);
        header.add(moneyLabel);

        JPanel toolbar = new JPanel(new BorderLayout());
        toolbar.setOpaque(false);

        JPanel leftTools = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        leftTools.setOpaque(false);

        // tools (esistenti)
        leftTools.add(toolButton("Drill", "drill_mk1", onSelectTool, onBuyToolRightClick));
        leftTools.add(toolButton("Belt", "conveyor_belt", onSelectTool, onBuyToolRightClick));
        leftTools.add(toolButton("Splitter", "splitter", onSelectTool, onBuyToolRightClick));
        leftTools.add(toolButton("Merger", "merger", onSelectTool, onBuyToolRightClick));
        leftTools.add(toolButton("Lift", "lift", onSelectTool, onBuyToolRightClick));
        leftTools.add(toolButton("Drop", "dropper", onSelectTool, onBuyToolRightClick));
        leftTools.add(toolButton("Chromator", "chromator", onSelectTool, onBuyToolRightClick));
        leftTools.add(toolButton("Mixer", "color_mixer", onSelectTool, onBuyToolRightClick));
        leftTools.add(toolButton("Shaper", "smoothing", onSelectTool, onBuyToolRightClick));
        leftTools.add(toolButton("Cutting", "cutting", onSelectTool, onBuyToolRightClick));

        // nuovi effetti (GDD)
        leftTools.add(toolButton("Shiny", "shiny_polisher", onSelectTool, onBuyToolRightClick));
        leftTools.add(toolButton("Blaze", "blazing_forge", onSelectTool, onBuyToolRightClick));
        leftTools.add(toolButton("Glitch", "glitch_distorter", onSelectTool, onBuyToolRightClick));

        leftTools.add(toolButton("Nexus", "nexus_core", onSelectTool, onBuyToolRightClick));

        leftTools.add(new JSeparator(SwingConstants.VERTICAL) {{
            setPreferredSize(new Dimension(5, 25));
        }});

        JButton btnStructure = UiKit.button("Structure", e -> {
            String blockId = JOptionPane.showInputDialog(
                    this,
                    "Enter Native Block ID (e.g., hytale:stone):",
                    "hytale:stone"
            );
            if (blockId != null && !blockId.isBlank()) onSelectStructureNativeId.accept(blockId);
        });
        btnStructure.setBackground(new Color(100, 100, 100));
        leftTools.add(btnStructure);

        leftTools.add(new JSeparator(SwingConstants.VERTICAL) {{
            setPreferredSize(new Dimension(5, 25));
        }});

        leftTools.add(UiKit.button("DOWN", e -> onLayerDelta.accept(-1)));
        leftTools.add(UiKit.button("UP", e -> onLayerDelta.accept(+1)));

        JPanel rightSystem = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 5));
        rightSystem.setOpaque(false);

        JButton btnSOS = UiKit.button("SOS", e -> onSOS.run());
        btnSOS.setBackground(new Color(220, 150, 0));

        JButton btnSave = UiKit.button("SAVE", e -> onSave.run());
        btnSave.setBackground(new Color(0, 100, 200));

        JButton btnReset = UiKit.button("RESET", e -> onReset.run());
        btnReset.setBackground(new Color(180, 0, 0));

        JButton btnDelete = UiKit.button("DELETE", e -> onDelete.run());
        btnDelete.setBackground(Color.BLACK);
        btnDelete.setForeground(Color.RED);
        btnDelete.setBorder(BorderFactory.createLineBorder(Color.RED, 1));

        rightSystem.add(btnSOS);
        rightSystem.add(btnSave);
        rightSystem.add(btnReset);
        rightSystem.add(btnDelete);

        toolbar.add(leftTools, BorderLayout.WEST);
        toolbar.add(rightSystem, BorderLayout.EAST);

        add(header, BorderLayout.NORTH);
        add(toolbar, BorderLayout.CENTER);
    }

    JComboBox<Object> getPlayerSelector() { return playerSelector; }
    JLabel getMoneyLabel() { return moneyLabel; }

    private JButton toolButton(String text,
                               String itemId,
                               Consumer<String> onSelectTool,
                               BiConsumer<String, Integer> onBuyToolRightClick) {

        JButton btn = UiKit.button(text, e -> onSelectTool.accept(itemId));

        btn.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    onBuyToolRightClick.accept(itemId, 1);
                }
            }
        });

        return btn;
    }
}
