package com.matterworks.core.database.dao;

import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.domain.matter.MatterColor;
import com.matterworks.core.domain.matter.MatterEffect;
import com.matterworks.core.domain.matter.MatterShape;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

public class MatterPricingDAO {

    private final DatabaseManager db;

    public MatterPricingDAO(DatabaseManager db) {
        this.db = db;
    }

    public Map<MatterShape, Double> loadShapeBasePrices() {
        String sql = "SELECT shape, base_price FROM matter_shape_base_price";
        Map<MatterShape, Double> out = new EnumMap<>(MatterShape.class);

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                MatterShape shape = parseEnumOrNull(rs.getString("shape"), MatterShape.class);
                if (shape == null) continue;

                double v = rs.getDouble("base_price");
                if (Double.isNaN(v) || Double.isInfinite(v)) continue;

                out.put(shape, v);
            }
        } catch (SQLException e) {
            // If tables aren't migrated yet, keep safe behavior.
            if (!isUnknownTable(e)) e.printStackTrace();
        }

        return out;
    }

    public Map<MatterColor, Double> loadColorBasePrices() {
        String sql = "SELECT color, base_price FROM matter_color_base_price";
        Map<MatterColor, Double> out = new EnumMap<>(MatterColor.class);

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                MatterColor color = parseEnumOrNull(rs.getString("color"), MatterColor.class);
                if (color == null) continue;

                double v = rs.getDouble("base_price");
                if (Double.isNaN(v) || Double.isInfinite(v)) continue;

                out.put(color, v);
            }
        } catch (SQLException e) {
            if (!isUnknownTable(e)) e.printStackTrace();
        }

        return out;
    }

    public Map<MatterEffect, Double> loadEffectBasePrices() {
        String sql = "SELECT effect, base_price FROM matter_effect_base_price";
        Map<MatterEffect, Double> out = new EnumMap<>(MatterEffect.class);

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                MatterEffect effect = parseEnumOrNull(rs.getString("effect"), MatterEffect.class);
                if (effect == null) continue;

                double v = rs.getDouble("base_price");
                if (Double.isNaN(v) || Double.isInfinite(v)) continue;

                out.put(effect, v);
            }
        } catch (SQLException e) {
            if (!isUnknownTable(e)) e.printStackTrace();
        }

        return out;
    }

    private static <T extends Enum<T>> T parseEnumOrNull(String raw, Class<T> type) {
        if (raw == null) return null;
        String v = raw.trim().toUpperCase(Locale.ROOT);
        if (v.isBlank()) return null;
        try {
            return Enum.valueOf(type, v);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isUnknownTable(SQLException e) {
        // MySQL/MariaDB "Table doesn't exist" SQLState is typically "42S02"
        // We keep it permissive to avoid blocking early migrations.
        String state = e.getSQLState();
        if (state != null && state.equalsIgnoreCase("42S02")) return true;

        String msg = e.getMessage();
        return msg != null && msg.toLowerCase(Locale.ROOT).contains("doesn't exist");
    }
}
