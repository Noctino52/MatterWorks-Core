package com.matterworks.core.ui.swing.panels;

import com.matterworks.core.domain.player.PlayerProfile;
import com.matterworks.core.domain.shop.VoidShopItem;
import com.matterworks.core.managers.GridManager;
import com.matterworks.core.ui.MariaDBAdapter;

import javax.swing.*;
import javax.swing.Timer;
import java.awt.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

public class VoidShopPanel extends JPanel {

    private final MariaDBAdapter repository;
    private final UUID playerUuid;
    private final GridManager gridManager;
    private final Runnable onEconomyMaybeChanged;

    private final JPanel listPanel = new JPanel();
    private final Map<String, Row> rows = new HashMap<>();

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "void-shop-panel");
        t.setDaemon(true);
        return t;
    });

    private final Timer refreshTimer;

    private volatile boolean disposed = false;

    private static final Color BG = new Color(30, 30, 35);
    private static final Color CARD = new Color(50, 50, 55);
    private static final Color PURPLE = new Color(190, 0, 220);

    private static final class Row {
        final String itemId;
        final JLabel lblTitle;
        final JLabel lblDetail;
        final JLabel lblOwned;
        final JButton btnBuy;
        final JButton btnUse;

        volatile int lastOwned = Integer.MIN_VALUE;

        Row(String itemId, JLabel lblTitle, JLabel lblDetail, JLabel lblOwned, JButton btnBuy, JButton btnUse) {
            this.itemId = itemId;
            this.lblTitle = lblTitle;
            this.lblDetail = lblDetail;
            this.lblOwned = lblOwned;
            this.btnBuy = btnBuy;
            this.btnUse = btnUse;
        }
    }

    public VoidShopPanel(MariaDBAdapter repository, UUID playerUuid, GridManager gm, Runnable onEconomyMaybeChanged) {
        this.repository = repository;
        this.playerUuid = playerUuid;
        this.gridManager = gm;
        this.onEconomyMaybeChanged = onEconomyMaybeChanged;

        setLayout(new BorderLayout());
        setBackground(BG);
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JLabel title = new JLabel("VOID SHOP");
        title.setForeground(PURPLE);
        title.setFont(new Font("Monospaced", Font.BOLD, 16));
        title.setHorizontalAlignment(SwingConstants.CENTER);

        JLabel subtitle = new JLabel("Premium items purchasable ONLY with VOID coins (no selling).");
        subtitle.setForeground(Color.LIGHT_GRAY);
        subtitle.setFont(new Font("SansSerif", Font.PLAIN, 11));
        subtitle.setHorizontalAlignment(SwingConstants.CENTER);

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(title);
        top.add(Box.createVerticalStrut(4));
        top.add(subtitle);
        top.add(Box.createVerticalStrut(10));

        add(top, BorderLayout.NORTH);

        listPanel.setOpaque(false);
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));

        JScrollPane scroll = new JScrollPane(listPanel);
        scroll.setBorder(null);
        scroll.getViewport().setOpaque(false);
        scroll.setOpaque(false);
        add(scroll, BorderLayout.CENTER);

        renderCatalog();

        refreshTimer = new Timer(700, e -> refreshStates());
        refreshTimer.start();

        refreshStates();
    }

    public void dispose() {
        disposed = true;
        try { if (refreshTimer != null) refreshTimer.stop(); } catch (Exception ignored) {}
        try { exec.shutdownNow(); } catch (Exception ignored) {}
    }

    private void renderCatalog() {
        listPanel.removeAll();
        rows.clear();

        List<VoidShopItem> catalog = Collections.emptyList();
        try {
            catalog = gridManager.getVoidShopCatalog();
        } catch (Throwable t) {
            t.printStackTrace();
        }

        if (catalog == null || catalog.isEmpty()) {
            JLabel empty = new JLabel("<html><div style='text-align:center;'>No items in Void Shop.<br/>Check DB table for void shop items.</div></html>");
            empty.setForeground(Color.LIGHT_GRAY);
            empty.setFont(new Font("SansSerif", Font.PLAIN, 12));
            empty.setAlignmentX(Component.CENTER_ALIGNMENT);

            listPanel.add(Box.createVerticalStrut(20));
            listPanel.add(empty);
            revalidate();
            repaint();
            return;
        }

        for (VoidShopItem it : catalog) {
            JPanel card = createCard(it);
            listPanel.add(card);
            listPanel.add(Box.createVerticalStrut(10));
        }

        revalidate();
        repaint();
    }

    private JPanel createCard(VoidShopItem it) {
        String id = it.itemId();

        JPanel card = new JPanel(new BorderLayout(10, 5));
        card.setBackground(CARD);
        card.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY, 1));
        card.setMaximumSize(new Dimension(360, 92));
        card.setPreferredSize(new Dimension(360, 92));

        JPanel info = new JPanel(new GridLayout(3, 1));
        info.setOpaque(false);
        info.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 0));

        String display = (it.displayName() != null && !it.displayName().isBlank()) ? it.displayName() : id;

        JLabel lblTitle = new JLabel(display);
        lblTitle.setForeground(Color.WHITE);
        lblTitle.setFont(new Font("SansSerif", Font.BOLD, 13));

        String desc = (it.description() != null) ? it.description() : "";
        JLabel lblDetail = new JLabel("Cost: " + it.voidPrice() + " VOID | " + desc);
        lblDetail.setForeground(new Color(200, 200, 200));
        lblDetail.setFont(new Font("Monospaced", Font.PLAIN, 10));

        JLabel lblOwned = new JLabel("Owned: ...");
        lblOwned.setForeground(Color.LIGHT_GRAY);
        lblOwned.setFont(new Font("Monospaced", Font.PLAIN, 11));

        info.add(lblTitle);
        info.add(lblDetail);
        info.add(lblOwned);

        JButton btnBuy = new JButton("BUY");
        btnBuy.setFont(new Font("SansSerif", Font.BOLD, 11));
        btnBuy.setFocusable(false);
        btnBuy.setBackground(new Color(120, 40, 180));
        btnBuy.setForeground(Color.WHITE);
        btnBuy.setToolTipText("Buy 1x for " + it.voidPrice() + " VOID coins");
        btnBuy.addActionListener(e -> buyAsync(id));

        JButton btnUse = new JButton("USE");
        btnUse.setFont(new Font("SansSerif", Font.BOLD, 11));
        btnUse.setFocusable(false);
        btnUse.setBackground(new Color(60, 120, 80));
        btnUse.setForeground(Color.WHITE);
        btnUse.setToolTipText("Use 1x from your inventory (if supported)");
        btnUse.addActionListener(e -> useAsync(id));

        JPanel right = new JPanel();
        right.setOpaque(false);
        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
        right.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 10));

        Dimension btnSize = new Dimension(90, 28);
        btnBuy.setPreferredSize(btnSize);
        btnBuy.setMaximumSize(btnSize);
        btnUse.setPreferredSize(btnSize);
        btnUse.setMaximumSize(btnSize);

        right.add(btnBuy);
        right.add(Box.createVerticalStrut(6));
        right.add(btnUse);

        card.add(info, BorderLayout.CENTER);
        card.add(right, BorderLayout.EAST);

        Row row = new Row(id, lblTitle, lblDetail, lblOwned, btnBuy, btnUse);
        rows.put(id, row);

        return card;
    }

    private void buyAsync(String itemId) {
        if (disposed) return;
        if (playerUuid == null) return;

        exec.submit(() -> {
            try {
                boolean ok = gridManager.buyVoidShopItem(playerUuid, itemId, 1);
                SwingUtilities.invokeLater(() -> {
                    if (!ok) {
                        JOptionPane.showMessageDialog(
                                this,
                                "Not enough VOID coins (or purchase not allowed).",
                                "Void Shop",
                                JOptionPane.WARNING_MESSAGE
                        );
                    }
                    if (onEconomyMaybeChanged != null) onEconomyMaybeChanged.run();
                    refreshStates();
                });
            } catch (Throwable t) {
                t.printStackTrace();
            }
        });
    }

    private void useAsync(String itemId) {
        if (disposed) return;
        if (playerUuid == null) return;

        exec.submit(() -> {
            try {
                if (!isOverclockItem(itemId)) {
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                            this,
                            "This item cannot be used from here yet.",
                            "Void Shop",
                            JOptionPane.INFORMATION_MESSAGE
                    ));
                    return;
                }

                boolean ok = false;

                // Prefer direct call if present
                try {
                    ok = gridManager.useOverclock(playerUuid, itemId);
                } catch (Throwable ignored) {
                    ok = false;
                }

                final boolean fOk = ok;
                SwingUtilities.invokeLater(() -> {
                    if (!fOk) {
                        JOptionPane.showMessageDialog(
                                this,
                                "Use failed: missing item (or not allowed).",
                                "Void Shop",
                                JOptionPane.WARNING_MESSAGE
                        );
                    } else {
                        JOptionPane.showMessageDialog(
                                this,
                                "Overclock activated!",
                                "Void Shop",
                                JOptionPane.INFORMATION_MESSAGE
                        );
                    }
                    if (onEconomyMaybeChanged != null) onEconomyMaybeChanged.run();
                    refreshStates();
                });

            } catch (Throwable t) {
                t.printStackTrace();
            }
        });
    }

    private void refreshStates() {
        if (disposed) return;
        if (playerUuid == null) return;

        PlayerProfile p = gridManager.getCachedProfile(playerUuid);
        if (p == null) return;

        int voidCoins = p.getVoidCoins();
        boolean admin = p.isAdmin();

        List<VoidShopItem> catalog = Collections.emptyList();
        try { catalog = gridManager.getVoidShopCatalog(); } catch (Throwable ignored) {}

        for (Row r : rows.values()) {
            int owned = 0;
            try { owned = repository.getInventoryItemCount(playerUuid, r.itemId); }
            catch (Throwable ignored) {}

            if (owned != r.lastOwned) {
                r.lastOwned = owned;
                r.lblOwned.setText("Owned: " + owned);
            }

            VoidShopItem def = null;
            try {
                if (catalog != null) {
                    for (VoidShopItem x : catalog) {
                        if (x != null && r.itemId.equals(x.itemId())) { def = x; break; }
                    }
                }
            } catch (Throwable ignored) {}

            int price = (def != null ? Math.max(0, def.voidPrice()) : Integer.MAX_VALUE);

            boolean canBuy = admin || voidCoins >= price;
            r.btnBuy.setEnabled(canBuy);
            r.btnBuy.setBackground(canBuy ? new Color(120, 40, 180) : new Color(90, 90, 40));

            // USE button rules
            boolean usable = isOverclockItem(r.itemId);

            boolean canUse;
            if (!usable) {
                canUse = false;
            } else {
                // If GridManager has canUseOverclock, use it; otherwise fallback to (admin || owned>0)
                Boolean canUseFromCore = tryCallBoolean(gridManager, "canUseOverclock",
                        new Class<?>[]{UUID.class, String.class},
                        new Object[]{playerUuid, r.itemId});
                canUse = (canUseFromCore != null) ? canUseFromCore : (admin || owned > 0);
            }

            r.btnUse.setEnabled(canUse);
            r.btnUse.setBackground(canUse ? new Color(60, 120, 80) : new Color(70, 70, 70));
        }
    }

    private boolean isOverclockItem(String itemId) {
        if (itemId == null) return false;
        return itemId.equals("overclock_2h")
                || itemId.equals("overclock_12h")
                || itemId.equals("overclock_24h")
                || itemId.equals("overclock_life")
                || itemId.equals("global_overclock_2h")
                || itemId.equals("global_overclock_12h")
                || itemId.equals("global_overclock_24h");
    }


    private static Boolean tryCallBoolean(Object target, String methodName, Class<?>[] argTypes, Object[] args) {
        try {
            Method m = target.getClass().getMethod(methodName, argTypes);
            Object r = m.invoke(target, args);
            if (r instanceof Boolean b) return b;
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
