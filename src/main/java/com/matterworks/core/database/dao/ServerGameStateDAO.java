package com.matterworks.core.database.dao;

import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.database.dao.SqlCompat;
import com.matterworks.core.ui.ServerConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

public class ServerGameStateDAO {

    private final DatabaseManager db;

    public ServerGameStateDAO(DatabaseManager db) {
        this.db = db;
    }

    // ==========================================================
    // FACTION ROTATION / ACTIVE FACTION
    // ==========================================================
    public int getActiveFactionId() {

        try (Connection conn = db.getConnection()) {

            // Support legacy column names too
            String col = SqlCompat.firstExistingColumn(conn, "server_gamestate",
                    "active_faction_id", "active_factionid", "active_faction_code");
            if (col == null) return 1;

            String sql = "SELECT " + col + " FROM server_gamestate WHERE id = 1";
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {

                if (!rs.next()) return 1;

                String raw = rs.getString(1);
                if (raw == null) return 1;

                String v = raw.trim();
                if (v.isEmpty()) return 1;

                // If numeric -> use it directly
                Integer asInt = tryParseInt(v);
                if (asInt != null) return Math.max(1, asInt);

                // Otherwise treat as faction CODE (legacy like "KWEEBEC") and resolve from DB
                int resolved = resolveFactionIdByCodeOrName(conn, v);
                return (resolved > 0) ? resolved : 1;
            }

        } catch (SQLException e) {
            // If column missing etc. fallback safely
            if (!SqlCompat.isUnknownColumn(e)) e.printStackTrace();
        }

        return 1;
    }

    public void setActiveFactionId(int factionId) {

        int v = Math.max(1, factionId);

        try (Connection conn = db.getConnection()) {

            String col = SqlCompat.firstExistingColumn(conn, "server_gamestate",
                    "active_faction_id", "active_factionid", "active_faction_code");
            if (col == null) return;

            String sql = "UPDATE server_gamestate SET " + col + " = ? WHERE id = 1";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                // write as String to support both VARCHAR and INT columns safely
                ps.setString(1, String.valueOf(v));
                ps.executeUpdate();
            }

        } catch (SQLException e) {
            if (!SqlCompat.isUnknownColumn(e)) e.printStackTrace();
        }
    }

    private static Integer tryParseInt(String v) {
        try {
            return Integer.parseInt(v);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int resolveFactionIdByCodeOrName(Connection conn, String token) {
        if (token == null) return -1;
        String t = token.trim();
        if (t.isEmpty()) return -1;

        // 1) match by code (case-insensitive)
        String sqlCode = "SELECT id FROM factions WHERE UPPER(code) = UPPER(?) LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sqlCode)) {
            ps.setString(1, t);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException ignored) {}

        // 2) fallback: match by display_name containing token (case-insensitive)
        // Works for legacy values like "KWEEBEC" with display_name = "Kweebec Grove Council"
        String sqlName = "SELECT id FROM factions WHERE UPPER(display_name) LIKE CONCAT('%', UPPER(?), '%') LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sqlName)) {
            ps.setString(1, t);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException ignored) {}

        // 3) extra fallback: common normalization (KWEEBEC -> Kweebec)
        String normalized = t.toLowerCase(Locale.ROOT);
        if (normalized.length() > 1) {
            normalized = Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
            try (PreparedStatement ps = conn.prepareStatement(sqlName)) {
                ps.setString(1, normalized);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getInt(1);
                }
            } catch (SQLException ignored) {}
        }

        return -1;
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

    public ServerConfig loadServerConfig() {

        // defaults "safe"
        double playerStartMoney = 1000.0;
        int plotStartVeinClusterRadiusPct = 18; // default = 18%
        int veinRaw = 3, veinRed = 1, veinBlue = 1, veinYellow = 0;
        double sosThreshold = 500.0;
        int maxInventoryMachine = 64;

        int plotStartingX = 25, plotStartingY = 25;
        int plotMaxX = 50, plotMaxY = 50;
        int plotIncreaseX = 2, plotIncreaseY = 2;

        // NEW: starting veins defaults (keep old hardcoded behavior as fallback)
        int plotStartVeinRaw = 2;
        int plotStartVeinRed = 1;
        int plotStartVeinBlue = 1;
        int plotStartVeinYellow = 1;

        // NEW: vertical cap defaults
        int plotHeightStart = 4;
        int plotHeightMax = 256;
        int plotHeightIncreasePerPrestige = 1;

        int prestigeVoidCoinsAdd = 0;
        int prestigePlotBonus = 0;
        double prestigeSellK = 0.0;

        // NEW defaults (prestige action fee)
        double prestigeActionCostBase = 0.0;
        double prestigeActionCostMult = 0.0;

        try (Connection conn = db.getConnection()) {

            boolean hasMaxInv = SqlCompat.columnExists(conn, "server_gamestate", "max_inventory_machine");

            // plot columns: legacy + snake_case
            String colStartVeinClusterPct = SqlCompat.firstExistingColumn(conn, "server_gamestate",
                    "plot_start_vein_cluster_radius_pct", "Plot_Start_Vein_Cluster_Radius_Pct", "plotStartVeinClusterRadiusPct");

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

            // NEW: starting veins columns
            String colStartVeinRaw = SqlCompat.firstExistingColumn(conn, "server_gamestate",
                    "plot_start_vein_raw", "Plot_Start_Vein_Raw", "plotStartVeinRaw");
            String colStartVeinRed = SqlCompat.firstExistingColumn(conn, "server_gamestate",
                    "plot_start_vein_red", "Plot_Start_Vein_Red", "plotStartVeinRed");
            String colStartVeinBlue = SqlCompat.firstExistingColumn(conn, "server_gamestate",
                    "plot_start_vein_blue", "Plot_Start_Vein_Blue", "plotStartVeinBlue");
            String colStartVeinYellow = SqlCompat.firstExistingColumn(conn, "server_gamestate",
                    "plot_start_vein_yellow", "Plot_Start_Vein_Yellow", "plotStartVeinYellow");

            // NEW: height columns (Y+ cap)
            String colPlotHStart = SqlCompat.firstExistingColumn(conn, "server_gamestate",
                    "plot_height_start", "Plot_Height_Start", "plotHeightStart");
            String colPlotHMax = SqlCompat.firstExistingColumn(conn, "server_gamestate",
                    "plot_height_max", "Plot_Height_Max", "plotHeightMax");
            String colPlotHInc = SqlCompat.firstExistingColumn(conn, "server_gamestate",
                    "plot_height_increase_per_prestige", "Plot_Height_Increase_Per_Prestige", "plotHeightIncreasePerPrestige");

            // prestige columns
            String colPrestigeVoid = SqlCompat.firstExistingColumn(conn, "server_gamestate", "prestige_void_coins_add");
            String colPrestigePlotBonus = SqlCompat.firstExistingColumn(conn, "server_gamestate", "prestige_plotbonus", "prestige_plot_bonus");
            String colPrestigeSellK = SqlCompat.firstExistingColumn(conn, "server_gamestate", "prestige_sell_k");

            // prestige action fee columns
            String colPrestigeActionBase = SqlCompat.firstExistingColumn(conn, "server_gamestate", "prestige_action_cost_base");
            String colPrestigeActionMult = SqlCompat.firstExistingColumn(conn, "server_gamestate", "prestige_action_cost_mult");

            StringBuilder sql = new StringBuilder();
            sql.append("SELECT ")
                    .append("player_start_money, vein_raw, vein_red, vein_blue, vein_yellow, sos_threshold");

            if (hasMaxInv) sql.append(", max_inventory_machine");

            if (colStartVeinClusterPct != null) sql.append(", ").append(colStartVeinClusterPct).append(" AS plot_start_vein_cluster_radius_pct");


            if (colPlotStartX != null) sql.append(", ").append(colPlotStartX).append(" AS plot_start_x");
            if (colPlotStartY != null) sql.append(", ").append(colPlotStartY).append(" AS plot_start_y");
            if (colPlotMaxX != null) sql.append(", ").append(colPlotMaxX).append(" AS plot_max_x");
            if (colPlotMaxY != null) sql.append(", ").append(colPlotMaxY).append(" AS plot_max_y");
            if (colPlotIncX != null) sql.append(", ").append(colPlotIncX).append(" AS plot_inc_x");
            if (colPlotIncY != null) sql.append(", ").append(colPlotIncY).append(" AS plot_inc_y");

            if (colStartVeinRaw != null) sql.append(", ").append(colStartVeinRaw).append(" AS plot_start_vein_raw");
            if (colStartVeinRed != null) sql.append(", ").append(colStartVeinRed).append(" AS plot_start_vein_red");
            if (colStartVeinBlue != null) sql.append(", ").append(colStartVeinBlue).append(" AS plot_start_vein_blue");
            if (colStartVeinYellow != null) sql.append(", ").append(colStartVeinYellow).append(" AS plot_start_vein_yellow");

            if (colPlotHStart != null) sql.append(", ").append(colPlotHStart).append(" AS plot_height_start");
            if (colPlotHMax != null) sql.append(", ").append(colPlotHMax).append(" AS plot_height_max");
            if (colPlotHInc != null) sql.append(", ").append(colPlotHInc).append(" AS plot_height_inc");

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

                    // NEW: starting veins values
                    if (colStartVeinRaw != null) plotStartVeinRaw = Math.max(0, rs.getInt("plot_start_vein_raw"));
                    if (colStartVeinRed != null) plotStartVeinRed = Math.max(0, rs.getInt("plot_start_vein_red"));
                    if (colStartVeinBlue != null) plotStartVeinBlue = Math.max(0, rs.getInt("plot_start_vein_blue"));
                    if (colStartVeinYellow != null) plotStartVeinYellow = Math.max(0, rs.getInt("plot_start_vein_yellow"));

                    if (colStartVeinClusterPct != null) {
                        plotStartVeinClusterRadiusPct = rs.getInt("plot_start_vein_cluster_radius_pct");
                        // clamp safety: 0..50 (0 = centro preciso, 50 = metà lato)
                        plotStartVeinClusterRadiusPct = Math.max(0, Math.min(50, plotStartVeinClusterRadiusPct));
                    }


                    // NEW: height values
                    if (colPlotHStart != null) plotHeightStart = Math.max(1, rs.getInt("plot_height_start"));
                    if (colPlotHMax != null) plotHeightMax = Math.max(plotHeightStart, rs.getInt("plot_height_max"));
                    if (colPlotHInc != null) plotHeightIncreasePerPrestige = Math.max(0, rs.getInt("plot_height_inc"));

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

                plotStartVeinRaw, plotStartVeinRed, plotStartVeinBlue, plotStartVeinYellow,plotStartVeinClusterRadiusPct,

                plotHeightStart,
                plotHeightMax,
                plotHeightIncreasePerPrestige,

                prestigeVoidCoinsAdd,
                prestigePlotBonus,
                prestigeSellK,

                prestigeActionCostBase,
                prestigeActionCostMult
        );
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




    public int getVoidPlotItemBreakerIncreased() {
        String sql = "SELECT void_plotitembreaker_increased FROM server_gamestate LIMIT 1";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return Math.max(0, rs.getInt(1));
        } catch (SQLException ignored) {}
        return 0;
    }

    public int addVoidPlotItemBreakerIncreased(int delta) {
        int d = delta;
        if (d == 0) return getVoidPlotItemBreakerIncreased();
        String sql = "UPDATE server_gamestate SET void_plotitembreaker_increased = GREATEST(0, void_plotitembreaker_increased + ?) ";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, d);
            ps.executeUpdate();
        } catch (SQLException ignored) {}
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

            if (rs.next()) {
                long v = rs.getLong("global_overclock_end_ms");
                return Math.max(0L, v);
            }
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
                double v = rs.getDouble("global_overclock_multiplier");
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

            if (rs.next()) {
                long v = rs.getLong("global_overclock_last_duration_seconds");
                return Math.max(0L, v);
            }
        } catch (SQLException e) {
            if (!SqlCompat.isUnknownColumn(e)) e.printStackTrace();
        }
        return 0L;
    }

    public void setGlobalOverclockState(long endEpochMs, double multiplier, long lastDurationSeconds) {
        String sql = "UPDATE server_gamestate SET global_overclock_end_ms = ?, global_overclock_multiplier = ?, global_overclock_last_duration_seconds = ? WHERE id = 1";

        long end = Math.max(0L, endEpochMs);
        double mult = multiplier;
        if (Double.isNaN(mult) || Double.isInfinite(mult) || mult <= 0.0) mult = 1.0;

        long dur = Math.max(0L, lastDurationSeconds);

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, end);
            ps.setDouble(2, mult);
            ps.setLong(3, dur);
            ps.executeUpdate();

        } catch (SQLException e) {
            if (!SqlCompat.isUnknownColumn(e)) e.printStackTrace();
        }
    }

    public int getFactionRotationHours() {
        String sql = "SELECT faction_rotation_hours FROM server_gamestate WHERE id = 1";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            if (rs.next()) return Math.max(0, rs.getInt(1));
        } catch (SQLException e) {
            if (!SqlCompat.isUnknownColumn(e)) e.printStackTrace();
        }
        return 0;
    }



}
