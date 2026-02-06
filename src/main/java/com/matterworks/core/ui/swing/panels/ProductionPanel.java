package com.matterworks.core.ui.swing.panels;

import com.matterworks.core.domain.telemetry.production.ProductionStatLine;
import com.matterworks.core.domain.telemetry.production.ProductionStatsView;
import com.matterworks.core.domain.telemetry.production.ProductionTimeWindow;
import com.matterworks.core.managers.GridManager;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class ProductionPanel extends JPanel {

    private static final Color BG = new Color(30, 30, 35);
    private static final Color CARD = new Color(50, 50, 55);
    private static final Color ACCENT = new Color(90, 180, 255);

    private static final DecimalFormat MONEY_FMT = new DecimalFormat("#,##0.00");

    private final UUID playerUuid;
    private final GridManager gridManager;

    private volatile ProductionTimeWindow currentWindow = ProductionTimeWindow.ONE_MINUTE;

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "production-panel-worker");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean refreshRunning = new AtomicBoolean(false);
    private final Timer refreshTimer;
    private volatile boolean disposed = false;

    // Top UI
    private final JLabel lblSummary = new JLabel("Loading...");
    private final JToggleButton btn1m = new JToggleButton("1m");
    private final JToggleButton btn5m = new JToggleButton("5m");
    private final JToggleButton btn10m = new JToggleButton("10m");

    // Models (single view)
    private final LinesModel producedModel = new LinesModel(false);
    private final LinesModel consumedModel = new LinesModel(false);
    private final LinesModel soldModel = new LinesModel(true);

    public ProductionPanel(UUID playerUuid, GridManager gridManager) {
        this.playerUuid = playerUuid;
        this.gridManager = gridManager;

        setLayout(new BorderLayout());
        setBackground(BG);
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        add(buildTop(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);

        refreshTimer = new Timer(650, e -> requestRefresh());
        refreshTimer.start();

        requestRefresh();
    }

    public void dispose() {
        disposed = true;
        try { if (refreshTimer != null) refreshTimer.stop(); } catch (Exception ignored) {}
        try { exec.shutdownNow(); } catch (Exception ignored) {}
    }

    private JComponent buildTop() {
        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("PRODUCTION");
        title.setForeground(ACCENT);
        title.setFont(new Font("Monospaced", Font.BOLD, 16));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel subtitle = new JLabel("Rolling window stats: produced / consumed / sold (Nexus only).");
        subtitle.setForeground(Color.LIGHT_GRAY);
        subtitle.setFont(new Font("SansSerif", Font.PLAIN, 11));
        subtitle.setAlignmentX(Component.CENTER_ALIGNMENT);

        JPanel controls = new JPanel(new BorderLayout());
        controls.setOpaque(false);
        controls.setBorder(BorderFactory.createEmptyBorder(8, 0, 6, 0));

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.X_AXIS));

        ButtonGroup g = new ButtonGroup();
        setupToggle(btn1m);
        setupToggle(btn5m);
        setupToggle(btn10m);
        g.add(btn1m);
        g.add(btn5m);
        g.add(btn10m);
        btn1m.setSelected(true);

        btn1m.addActionListener(e -> setWindow(ProductionTimeWindow.ONE_MINUTE));
        btn5m.addActionListener(e -> setWindow(ProductionTimeWindow.FIVE_MINUTES));
        btn10m.addActionListener(e -> setWindow(ProductionTimeWindow.TEN_MINUTES));

        JLabel lbl = new JLabel("Window: ");
        lbl.setForeground(Color.LIGHT_GRAY);
        lbl.setFont(new Font("SansSerif", Font.BOLD, 11));

        left.add(lbl);
        left.add(Box.createHorizontalStrut(6));
        left.add(btn1m);
        left.add(Box.createHorizontalStrut(6));
        left.add(btn5m);
        left.add(Box.createHorizontalStrut(6));
        left.add(btn10m);

        lblSummary.setForeground(Color.WHITE);
        lblSummary.setFont(new Font("Monospaced", Font.PLAIN, 11));
        lblSummary.setHorizontalAlignment(SwingConstants.RIGHT);

        controls.add(left, BorderLayout.WEST);
        controls.add(lblSummary, BorderLayout.EAST);

        top.add(title);
        top.add(Box.createVerticalStrut(4));
        top.add(subtitle);
        top.add(controls);

        return top;
    }

    private void setupToggle(AbstractButton b) {
        b.setFocusable(false);
        b.setFont(new Font("SansSerif", Font.BOLD, 11));
        b.setBackground(new Color(70, 70, 80));
        b.setForeground(Color.WHITE);
        b.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY, 1));
        b.setPreferredSize(new Dimension(54, 24));
    }

    private JComponent buildCenter() {
        JPanel wrap = new JPanel();
        wrap.setOpaque(false);
        wrap.setLayout(new BoxLayout(wrap, BoxLayout.Y_AXIS));

        wrap.add(buildTableCard("Produced (Matters + Color-only)", producedModel));
        wrap.add(Box.createVerticalStrut(10));
        wrap.add(buildTableCard("Consumed (Matters + Color-only)", consumedModel));
        wrap.add(Box.createVerticalStrut(10));
        wrap.add(buildTableCard("Sold @ Nexus (Matters + Color-only)", soldModel));
        wrap.add(Box.createVerticalGlue());

        JScrollPane scroll = new JScrollPane(wrap);
        scroll.setBorder(null);
        scroll.getViewport().setOpaque(false);
        scroll.setOpaque(false);
        return scroll;
    }

    private JComponent buildTableCard(String title, LinesModel model) {
        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(CARD);
        card.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(Color.DARK_GRAY, 1),
                title,
                TitledBorder.LEFT,
                TitledBorder.TOP,
                new Font("SansSerif", Font.BOLD, 11),
                Color.WHITE
        ));
        card.setMaximumSize(new Dimension(380, 220));

        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);
        table.setRowHeight(20);
        table.setFont(new Font("Monospaced", Font.PLAIN, 11));
        table.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 11));
        table.getTableHeader().setReorderingAllowed(false);

        table.setBackground(new Color(45, 45, 50));
        table.setForeground(Color.WHITE);
        table.getTableHeader().setBackground(new Color(60, 60, 70));
        table.getTableHeader().setForeground(Color.WHITE);

        if (model.isSold()) {
            table.getColumnModel().getColumn(0).setPreferredWidth(220);
            table.getColumnModel().getColumn(1).setPreferredWidth(70);
            table.getColumnModel().getColumn(2).setPreferredWidth(80);
        } else {
            table.getColumnModel().getColumn(0).setPreferredWidth(260);
            table.getColumnModel().getColumn(1).setPreferredWidth(90);
        }

        JScrollPane sp = new JScrollPane(table);
        sp.setBorder(null);

        card.add(sp, BorderLayout.CENTER);
        return card;
    }

    private void setWindow(ProductionTimeWindow w) {
        if (w == null) w = ProductionTimeWindow.ONE_MINUTE;
        if (w == currentWindow) return;
        currentWindow = w;
        requestRefresh();
    }

    private void requestRefresh() {
        if (disposed) return;

        if (playerUuid == null || gridManager == null) {
            applyEmpty();
            return;
        }

        if (!refreshRunning.compareAndSet(false, true)) return;

        ProductionTimeWindow w = currentWindow;

        exec.submit(() -> {
            try {
                ProductionStatsView view = gridManager.getProductionStatsView(playerUuid, w);

                SwingUtilities.invokeLater(() -> applyView(view));
            } catch (Throwable t) {
                t.printStackTrace();
                SwingUtilities.invokeLater(this::applyEmpty);
            } finally {
                refreshRunning.set(false);
            }
        });
    }

    private void applyEmpty() {
        producedModel.setLines(List.of());
        consumedModel.setLines(List.of());
        soldModel.setLines(List.of());
        lblSummary.setText("No data.");
    }

    private void applyView(ProductionStatsView view) {
        if (disposed) return;

        if (view == null) {
            applyEmpty();
            return;
        }

        // ✅ Single list: matters already include "color-only" payloads via M:LIQUID:<COLOR>:NO_EFFECT
        producedModel.setLines(view.getProducedMatters());
        consumedModel.setLines(view.getConsumedMatters());
        soldModel.setLines(view.getSoldMatters());

        lblSummary.setText(
                "P: " + view.getTotalProduced()
                        + " | C: " + view.getTotalConsumed()
                        + " | S: " + view.getTotalSoldQuantity()
                        + " | $: " + MONEY_FMT.format(view.getTotalMoneyEarned())
        );
    }

    // =========================
    // Table model
    // =========================
    private static final class LinesModel extends AbstractTableModel {

        private final boolean sold;
        private final List<ProductionStatLine> lines = new ArrayList<>();

        LinesModel(boolean sold) {
            this.sold = sold;
        }

        boolean isSold() {
            return sold;
        }

        void setLines(List<ProductionStatLine> newLines) {
            lines.clear();
            if (newLines != null && !newLines.isEmpty()) lines.addAll(newLines);
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return lines.size();
        }

        @Override
        public int getColumnCount() {
            return sold ? 3 : 2;
        }

        @Override
        public String getColumnName(int column) {
            if (!sold) {
                return switch (column) {
                    case 0 -> "Item";
                    case 1 -> "Qty";
                    default -> "";
                };
            }
            return switch (column) {
                case 0 -> "Item";
                case 1 -> "Qty";
                case 2 -> "Money";
                default -> "";
            };
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            ProductionStatLine l = lines.get(rowIndex);

            if (!sold) {
                return switch (columnIndex) {
                    case 0 -> l.getLabel();
                    case 1 -> l.getQuantity();
                    default -> "";
                };
            }

            return switch (columnIndex) {
                case 0 -> l.getLabel();
                case 1 -> l.getQuantity();
                case 2 -> MONEY_FMT.format(l.getMoneyEarned());
                default -> "";
            };
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return false;
        }
    }
}
