package com.matterworks.core.database.dao;

import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.ui.ServerConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class ServerGameStateDAO {

    private final DatabaseManager db;

    public ServerGameStateDAO(DatabaseManager db) {
        this.db = db;
    }

    public int getDefaultItemPlacedOnPlotCap() {
        String sql = "SELECT default_item_placed_on_plot FROM server_gamestate WHERE id = 1";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            if (rs.next()) {
                int v = rs.getInt("default_item_placed_on_plot");
                return Math.max(1, v);
            }
        } catch (SQLException e) {
            if (!SqlCompat.isUnknownColumn(e)) e.printStackTrace();
        }
        return 1000;
    }

    public int getItemCapIncreaseStep() {
        String sql = "SELECT itemcap_increase_step FROM server_gamestate WHERE id = 1";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            if (rs.next()) return Math.max(0, rs.getInt("itemcap_increase_step"));
        } catch (SQLException e) {
            if (!SqlCompat.isUnknownColumn(e)) e.printStackTrace();
        }
        return 0;
    }

    /**
     * Extra additive step for the item cap, used by the "void/admin" button mechanic.
     * If the column does not exist (older DB), returns 0.
     */
    public int getVoidItemCapIncreaseStep() {
        String sql = "SELECT itemcap_void_increase_step FROM server_gamestate WHERE id = 1";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            if (rs.next()) return Math.max(0, rs.getInt("itemcap_void_increase_step"));
        } catch (SQLException e) {
            if (!SqlCompat.isUnknownColumn(e)) e.printStackTrace();
        }

        String sql2 = "SELECT void_itemcap_increase_step FROM server_gamestate WHERE id = 1";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql2);
             ResultSet rs = ps.executeQuery()) {

            if (rs.next()) return Math.max(0, rs.getInt("void_itemcap_increase_step"));
        } catch (SQLException e) {
            if (!SqlCompat.isUnknownColumn(e)) e.printStackTrace();
        }

        return 0;
    }

    public int getMaxItemPlacedOnPlotCap() {
        String sql = "SELECT max_item_placed_on_plot FROM server_gamestate WHERE id = 1";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            if (rs.next()) {
                int v = rs.getInt("max_item_placed_on_plot");
                return (v <= 0) ? Integer.MAX_VALUE : v;
            }
        } catch (SQLException e) {
            if (!SqlCompat.isUnknownColumn(e)) e.printStackTrace();
        }
        return Integer.MAX_VALUE;
    }

    public void updateMaxItemPlacedOnPlotCap(int newCap) {
        String sql = "UPDATE server_gamestate SET max_item_placed_on_plot = ? WHERE id = 1";
        int cap = (newCap <= 0) ? 0 : newCap; // 0 = unlimited
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, cap);
            ps.executeUpdate();
        } catch (SQLException e) {
            if (!SqlCompat.isUnknownColumn(e)) e.printStackTrace();
        }
    }

    public int loadMinutesToInactive() {
        String sql = "SELECT MinutesToInactive FROM server_gamestate WHERE id = 1";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            if (rs.next()) {
                int v = rs.getInt("MinutesToInactive");
                return Math.max(1, v);
            }
        } catch (SQLException ignored) {}
        return 5;
    }

    public ServerConfig loadServerConfig() {

        // defaults "safe"
        double playerStartMoney = 1000.0;
        int veinRaw = 3, veinRed = 1, veinBlue = 1, veinYellow = 0;
        double sosThreshold = 500.0;
        int maxInventoryMachine = 64;

        int plotStartingX = 25, plotStartingY = 25;
        int plotMaxX = 50, plotMaxY = 50;
        int plotIncreaseX = 2, plotIncreaseY = 2;

        int prestigeVoidCoinsAdd = 0;
        int prestigePlotBonus = 0;
        double prestigeSellK = 0.0;

        // NEW defaults
        double prestigeActionCostBase = 0.0;
        double prestigeActionCostMult = 0.0;

        try (Connection conn = db.getConnection()) {

            boolean hasMaxInv = SqlCompat.columnExists(conn, "server_gamestate", "max_inventory_machine");

            // plot columns: legacy + snake_case
            String colPlotStartX = SqlCompat.firstExistingColumn(conn, "server_gamestate",
                    "plot_start_x", "Plot_Starting_X", "plot_starting_x");
            String colPlotStartY = SqlCompat.firstExistingColumn(conn, "server_gamestate",
                    "plot_start_y", "Plot_Starting_Y", "plot_starting_y");
            String colPlotMaxX = SqlCompat.firstExistingColumn(conn, "server_gamestate",
                    "plot_max_x", "Plot_Max_X", "plot_max_x");
            String colPlotMaxY = SqlCompat.firstExistingColumn(conn, "server_gamestate",
                    "plot_max_y", "Plot_Max_Y", "plot_max_y");
            String colPlotIncX = SqlCompat.firstExistingColumn(conn, "server_gamestate",
                    "plot_increase_x", "Plot_IncreaseX", "Plot_Increase_X", "plot_increasex");
            String colPlotIncY = SqlCompat.firstExistingColumn(conn, "server_gamestate",
                    "plot_increase_y", "Plot_IncreaseY", "Plot_Increase_Y", "plot_increasey");

            // prestige columns
            String colPrestigeVoid = SqlCompat.firstExistingColumn(conn, "server_gamestate", "prestige_void_coins_add");
            String colPrestigePlotBonus = SqlCompat.firstExistingColumn(conn, "server_gamestate", "prestige_plotbonus", "prestige_plot_bonus");
            String colPrestigeSellK = SqlCompat.firstExistingColumn(conn, "server_gamestate", "prestige_sell_k");

            // NEW: prestige action fee columns
            String colPrestigeActionBase = SqlCompat.firstExistingColumn(conn, "server_gamestate", "prestige_action_cost_base");
            String colPrestigeActionMult = SqlCompat.firstExistingColumn(conn, "server_gamestate", "prestige_action_cost_mult");

            StringBuilder sql = new StringBuilder();
            sql.append("SELECT ")
                    .append("player_start_money, vein_raw, vein_red, vein_blue, vein_yellow, sos_threshold");

            if (hasMaxInv) sql.append(", max_inventory_machine");

            if (colPlotStartX != null) sql.append(", ").append(colPlotStartX).append(" AS plot_start_x");
            if (colPlotStartY != null) sql.append(", ").append(colPlotStartY).append(" AS plot_start_y");
            if (colPlotMaxX != null) sql.append(", ").append(colPlotMaxX).append(" AS plot_max_x");
            if (colPlotMaxY != null) sql.append(", ").append(colPlotMaxY).append(" AS plot_max_y");
            if (colPlotIncX != null) sql.append(", ").append(colPlotIncX).append(" AS plot_inc_x");
            if (colPlotIncY != null) sql.append(", ").append(colPlotIncY).append(" AS plot_inc_y");

            if (colPrestigeVoid != null) sql.append(", ").append(colPrestigeVoid).append(" AS prestige_void_coins_add");
            if (colPrestigePlotBonus != null) sql.append(", ").append(colPrestigePlotBonus).append(" AS prestige_plotbonus");
            if (colPrestigeSellK != null) sql.append(", ").append(colPrestigeSellK).append(" AS prestige_sell_k");

            if (colPrestigeActionBase != null) sql.append(", ").append(colPrestigeActionBase).append(" AS prestige_action_cost_base");
            if (colPrestigeActionMult != null) sql.append(", ").append(colPrestigeActionMult).append(" AS prestige_action_cost_mult");

            sql.append(" FROM server_gamestate WHERE id = 1");

            try (PreparedStatement ps = conn.prepareStatement(sql.toString());
                 ResultSet rs = ps.executeQuery()) {

                if (rs.next()) {
                    playerStartMoney = rs.getDouble("player_start_money");
                    veinRaw = rs.getInt("vein_raw");
                    veinRed = rs.getInt("vein_red");
                    veinBlue = rs.getInt("vein_blue");
                    veinYellow = rs.getInt("vein_yellow");
                    sosThreshold = rs.getDouble("sos_threshold");

                    if (hasMaxInv) maxInventoryMachine = Math.max(1, rs.getInt("max_inventory_machine"));

                    if (colPlotStartX != null) plotStartingX = Math.max(1, rs.getInt("plot_start_x"));
                    if (colPlotStartY != null) plotStartingY = Math.max(1, rs.getInt("plot_start_y"));
                    if (colPlotMaxX != null) plotMaxX = Math.max(plotStartingX, rs.getInt("plot_max_x"));
                    if (colPlotMaxY != null) plotMaxY = Math.max(plotStartingY, rs.getInt("plot_max_y"));
                    if (colPlotIncX != null) plotIncreaseX = Math.max(1, rs.getInt("plot_inc_x"));
                    if (colPlotIncY != null) plotIncreaseY = Math.max(1, rs.getInt("plot_inc_y"));

                    if (colPrestigeVoid != null) prestigeVoidCoinsAdd = Math.max(0, rs.getInt("prestige_void_coins_add"));
                    if (colPrestigePlotBonus != null) prestigePlotBonus = Math.max(0, rs.getInt("prestige_plotbonus"));
                    if (colPrestigeSellK != null) prestigeSellK = Math.max(0.0, rs.getDouble("prestige_sell_k"));

                    if (colPrestigeActionBase != null) prestigeActionCostBase = Math.max(0.0, rs.getDouble("prestige_action_cost_base"));
                    if (colPrestigeActionMult != null) prestigeActionCostMult = Math.max(0.0, rs.getDouble("prestige_action_cost_mult"));
                }
            }

        } catch (SQLException ignored) {}

        return new ServerConfig(
                playerStartMoney,
                veinRaw, veinRed, veinBlue, veinYellow,
                sosThreshold,
                maxInventoryMachine,
                plotStartingX, plotStartingY,
                plotMaxX, plotMaxY,
                plotIncreaseX, plotIncreaseY,
                prestigeVoidCoinsAdd,
                prestigePlotBonus,
                prestigeSellK,
                prestigeActionCostBase,
                prestigeActionCostMult
        );
    }


    public int getVoidPlotItemBreakerIncreased() {
        String sql = "SELECT void_plotitembreaker_increased FROM server_gamestate LIMIT 1";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return Math.max(0, rs.getInt(1));
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    public int addVoidPlotItemBreakerIncreased(int delta) {
        delta = Math.max(0, delta);
        if (delta <= 0) return getVoidPlotItemBreakerIncreased();

        String up = "UPDATE server_gamestate SET void_plotitembreaker_increased = GREATEST(0, void_plotitembreaker_increased + ?)";
        String sel = "SELECT void_plotitembreaker_increased FROM server_gamestate LIMIT 1";

        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);

            try (PreparedStatement ps = conn.prepareStatement(up)) {
                ps.setInt(1, delta);
                ps.executeUpdate();
            }

            int out = 0;
            try (PreparedStatement ps = conn.prepareStatement(sel);
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) out = Math.max(0, rs.getInt(1));
            }

            conn.commit();
            return out;
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return getVoidPlotItemBreakerIncreased();
    }

    // ==========================================================
    // GLOBAL OVERCLOCK (server-wide, real-time)
    // ==========================================================

    public long getGlobalOverclockEndEpochMs() {
        String sql = "SELECT global_overclock_end_ms FROM server_gamestate WHERE id = 1";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            if (rs.next()) return Math.max(0L, rs.getLong(1));
        } catch (SQLException e) {
            if (!SqlCompat.isUnknownColumn(e)) e.printStackTrace();
        }
        return 0L;
    }

    public double getGlobalOverclockMultiplier() {
        String sql = "SELECT global_overclock_multiplier FROM server_gamestate WHERE id = 1";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            if (rs.next()) {
                double v = rs.getDouble(1);
                if (Double.isNaN(v) || Double.isInfinite(v) || v <= 0.0) return 1.0;
                return v;
            }
        } catch (SQLException e) {
            if (!SqlCompat.isUnknownColumn(e)) e.printStackTrace();
        }
        return 1.0;
    }

    public long getGlobalOverclockLastDurationSeconds() {
        String sql = "SELECT global_overclock_last_duration_seconds FROM server_gamestate WHERE id = 1";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            if (rs.next()) return Math.max(0L, rs.getLong(1));
        } catch (SQLException e) {
            if (!SqlCompat.isUnknownColumn(e)) e.printStackTrace();
        }
        return 0L;
    }

    public void setGlobalOverclockState(long endEpochMs, double multiplier, long lastDurationSeconds) {
        long endMs = Math.max(0L, endEpochMs);
        double mult = multiplier;
        if (Double.isNaN(mult) || Double.isInfinite(mult) || mult <= 0.0) mult = 1.0;

        long lastDur = Math.max(0L, lastDurationSeconds);

        String sql = "UPDATE server_gamestate SET global_overclock_end_ms = ?, global_overclock_multiplier = ?, global_overclock_last_duration_seconds = ? WHERE id = 1";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, endMs);
            ps.setDouble(2, mult);
            ps.setLong(3, lastDur);
            ps.executeUpdate();

        } catch (SQLException e) {
            if (!SqlCompat.isUnknownColumn(e)) e.printStackTrace();
        }
    }
}
