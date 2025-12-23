package com.matterworks.core.infrastructure.swing;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;

final class UiKit {
    private UiKit() {}

    static JButton button(String text, ActionListener action) {
        JButton btn = new JButton(text);
        btn.setFocusable(false);
        btn.addActionListener(action);
        btn.setBackground(new Color(70, 70, 70));
        btn.setForeground(Color.WHITE);
        return btn;
    }

    static JLabel label(String text, Color color, int size) {
        JLabel lbl = new JLabel(text);
        lbl.setForeground(color);
        lbl.setFont(new Font("Monospaced", Font.BOLD, size));
        return lbl;
    }
}
