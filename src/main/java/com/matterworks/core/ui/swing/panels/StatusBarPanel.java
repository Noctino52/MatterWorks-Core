package com.matterworks.core.ui.swing.panels;

import com.matterworks.core.ui.swing.kit.UiKit;

import javax.swing.*;
import java.awt.*;

public final class StatusBarPanel extends JPanel {

    private final JLabel lblTool = UiKit.label("TOOL: ---", Color.WHITE, 12);
    private final JLabel lblOrient = UiKit.label("DIR: ---", Color.WHITE, 12);
    private final JLabel lblLayer = UiKit.label("LAYER: 0", Color.CYAN, 12);

    private final JLabel lblPlotItems = UiKit.label("ITEMS: ---/---", Color.LIGHT_GRAY, 12);
    private final JLabel lblPlotId = UiKit.label("PLOT ID: #---", Color.LIGHT_GRAY, 12);

    public StatusBarPanel() {
        super(new BorderLayout());
        setBackground(new Color(35, 35, 35));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 20, 5));
        left.setOpaque(false);
        left.add(lblTool);
        left.add(lblOrient);
        left.add(lblLayer);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 16, 5));
        right.setOpaque(false);
        right.add(lblPlotItems);
        right.add(lblPlotId);

        add(left, BorderLayout.WEST);
        add(right, BorderLayout.EAST);
    }

    public void setTool(String t) { lblTool.setText("TOOL: " + (t != null ? t : "---")); }
    public void setDir(String d) { lblOrient.setText("DIR: " + (d != null ? d : "---")); }
    public void setLayer(int y) { lblLayer.setText("LAYER: " + y); }

    public void setPlotId(String s) { lblPlotId.setText(s != null ? s : "PLOT ID: ---"); }

    public void setPlotItemsUnknown() {
        lblPlotItems.setText("ITEMS: ---/---");
        lblPlotItems.setForeground(Color.LIGHT_GRAY);
    }

    public void setPlotItems(int placed, int cap) {
        if (cap <= 0) {
            lblPlotItems.setText("ITEMS: " + placed + "/---");
            lblPlotItems.setForeground(Color.LIGHT_GRAY);
            return;
        }

        lblPlotItems.setText("ITEMS: " + placed + "/" + cap);

        // ✅ ROSSO se superi il cap
        if (placed > cap) {
            lblPlotItems.setForeground(Color.RED);
        } else {
            lblPlotItems.setForeground(Color.LIGHT_GRAY);
        }
    }

    JLabel getPlotIdLabel() { return lblPlotId; }
}
