package com.matterworks.core.ui.swing.panels;

import com.matterworks.core.ui.swing.kit.UiKit;

import javax.swing.*;
import java.awt.*;

public final class StatusBarPanel extends JPanel {

    private final JLabel lblTool = UiKit.label("TOOL: ---", Color.WHITE, 12);
    private final JLabel lblOrient = UiKit.label("DIR: ---", Color.WHITE, 12);
    private final JLabel lblLayer = UiKit.label("LAYER: 0", Color.CYAN, 12);

    private final JLabel lblPlotItems = UiKit.label("ITEMS: ---/---", Color.LIGHT_GRAY, 12);
    private final JButton btnItemCapPlus = tinyButton("+");
    private final JLabel lblPlotArea = UiKit.label("PLOT: ---", Color.LIGHT_GRAY, 12);
    private final JLabel lblPlotId = UiKit.label("PLOT ID: #---", Color.LIGHT_GRAY, 12);

    private final JButton btnPlotMinus = tinyButton("-");
    private final JButton btnPlotPlus  = tinyButton("+");

    public StatusBarPanel() {
        super(new BorderLayout());
        setBackground(new Color(35, 35, 35));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 20, 5));
        left.setOpaque(false);
        left.add(lblTool);
        left.add(lblOrient);
        left.add(lblLayer);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 5));
        right.setOpaque(false);

        right.add(lblPlotItems);
        right.add(btnItemCapPlus);
        right.add(lblPlotArea);
        right.add(btnPlotMinus);
        right.add(btnPlotPlus);
        right.add(lblPlotId);

        add(left, BorderLayout.WEST);
        add(right, BorderLayout.EAST);

        setPlotResizeEnabled(false);
        setItemCapIncreaseEnabled(true); // NOW enabled by default (testing)
    }

    private static JButton tinyButton(String text) {
        JButton b = new JButton(text);
        b.setFocusable(false);
        b.setMargin(new Insets(0, 8, 0, 8));
        b.setFont(new Font("SansSerif", Font.BOLD, 12));
        b.setPreferredSize(new Dimension(40, 22));
        return b;
    }

    // ---- base status ----
    public void setTool(String t) { lblTool.setText("TOOL: " + (t != null ? t : "---")); }
    public void setDir(String d) { lblOrient.setText("DIR: " + (d != null ? d : "---")); }
    public void setLayer(int y) { lblLayer.setText("LAYER: " + y); }

    public void setPlotId(String s) { lblPlotId.setText(s != null ? s : "PLOT ID: ---"); }

    // ---- items cap ----
    public void setPlotItemsUnknown() {
        lblPlotItems.setText("ITEMS: ---/---");
        lblPlotItems.setForeground(Color.LIGHT_GRAY);
        lblPlotItems.setToolTipText(null);
    }

    public void setPlotItems(int placed, int cap) {
        if (cap <= 0) {
            lblPlotItems.setText("ITEMS: " + placed + "/---");
            lblPlotItems.setForeground(Color.LIGHT_GRAY);
            return;
        }

        lblPlotItems.setText("ITEMS: " + placed + "/" + cap);
        lblPlotItems.setForeground(placed >= cap ? Color.RED : Color.LIGHT_GRAY);
    }

    public void setPlotItems(int placed, int cap, String tooltip) {
        setPlotItems(placed, cap);
        lblPlotItems.setToolTipText(tooltip);
    }

    // ---- plot area ----
    public void setPlotAreaUnknown() {
        lblPlotArea.setText("PLOT: ---");
        lblPlotArea.setForeground(Color.LIGHT_GRAY);
    }

    public void setPlotAreaText(String areaText) {
        lblPlotArea.setText("PLOT: " + (areaText != null ? areaText : "---"));
        lblPlotArea.setForeground(Color.LIGHT_GRAY);
    }

    // ---- resize buttons ----
    public void setPlotResizeEnabled(boolean enabled) {
        btnPlotMinus.setEnabled(enabled);
        btnPlotPlus.setEnabled(enabled);
    }

    // ---- item cap increase button ----
    public void setItemCapIncreaseEnabled(boolean enabled) {
        btnItemCapPlus.setEnabled(enabled);
    }

    public void setItemCapIncreaseAction(Runnable onPlus) {
        for (var al : btnItemCapPlus.getActionListeners()) btnItemCapPlus.removeActionListener(al);
        if (onPlus != null) btnItemCapPlus.addActionListener(e -> onPlus.run());
    }

    public void setPlotResizeActions(Runnable onMinus, Runnable onPlus) {
        for (var al : btnPlotMinus.getActionListeners()) btnPlotMinus.removeActionListener(al);
        for (var al : btnPlotPlus.getActionListeners()) btnPlotPlus.removeActionListener(al);

        if (onMinus != null) btnPlotMinus.addActionListener(e -> onMinus.run());
        if (onPlus != null) btnPlotPlus.addActionListener(e -> onPlus.run());
    }

    JLabel getPlotIdLabel() { return lblPlotId; }
}
