package com.matterworks.core.database.dao;

import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.domain.factions.FactionDefinition;
import com.matterworks.core.domain.factions.FactionPricingRule;
import com.matterworks.core.domain.factions.FactionRuleEnums;
import com.matterworks.core.domain.matter.MatterColor;
import com.matterworks.core.domain.matter.MatterEffect;
import com.matterworks.core.domain.matter.MatterShape;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class FactionDAO {

    private final DatabaseManager db;

    public FactionDAO(DatabaseManager db) {
        this.db = db;
    }

    public List<FactionDefinition> loadAllFactions() {
        String sql = """
                SELECT id, code, display_name, description, sort_order
                FROM factions
                ORDER BY sort_order ASC, id ASC
                """;

        List<FactionDefinition> out = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                out.add(new FactionDefinition(
                        rs.getInt("id"),
                        rs.getString("code"),
                        rs.getString("display_name"),
                        rs.getString("description"),
                        rs.getInt("sort_order")
                ));
            }
        } catch (SQLException e) {
            // If the table does not exist yet, keep safe behavior (empty list).
            if (!isUnknownTable(e)) e.printStackTrace();
        }
        return out;
    }

    public List<FactionPricingRule> loadRulesForFaction(int factionId) {
        String sql = """
                SELECT id, faction_id, sentiment, match_type, combine_mode,
                       color, shape, effect, multiplier, priority, note
                FROM faction_pricing_rules
                WHERE faction_id = ?
                ORDER BY id ASC
                """;

        List<FactionPricingRule> out = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, factionId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapRule(rs));
                }
            }
        } catch (SQLException e) {
            if (!isUnknownTable(e)) e.printStackTrace();
        }
        return out;
    }

    private FactionPricingRule mapRule(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        int factionId = rs.getInt("faction_id");

        var sentiment = parseEnum(rs.getString("sentiment"), FactionRuleEnums.Sentiment.class, FactionRuleEnums.Sentiment.LIKE);
        var matchType = parseEnum(rs.getString("match_type"), FactionRuleEnums.MatchType.class, FactionRuleEnums.MatchType.CONTAINS);
        var combineMode = parseEnum(rs.getString("combine_mode"), FactionRuleEnums.CombineMode.class, FactionRuleEnums.CombineMode.ALL);

        MatterColor color = parseEnumOrNull(rs.getString("color"), MatterColor.class);
        MatterShape shape = parseEnumOrNull(rs.getString("shape"), MatterShape.class);
        MatterEffect effect = parseEnumOrNull(rs.getString("effect"), MatterEffect.class);

        double mult = rs.getDouble("multiplier");
        if (Double.isNaN(mult) || Double.isInfinite(mult) || mult <= 0.0) mult = 1.0;

        int priority = rs.getInt("priority");
        String note = rs.getString("note");

        return new FactionPricingRule(
                id,
                factionId,
                sentiment,
                matchType,
                combineMode,
                color,
                shape,
                effect,
                mult,
                priority,
                note
        );
    }

    private static <T extends Enum<T>> T parseEnum(String raw, Class<T> type, T fallback) {
        if (raw == null) return fallback;
        String v = raw.trim().toUpperCase(Locale.ROOT);
        if (v.isBlank()) return fallback;
        try {
            return Enum.valueOf(type, v);
        } catch (Throwable ignored) {
            return fallback;
        }
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
        // MySQL/MariaDB: SQLState 42S02 = Base table or view not found
        String state = e.getSQLState();
        if ("42S02".equals(state)) return true;

        String msg = e.getMessage();
        if (msg == null) return false;

        String m = msg.toLowerCase(Locale.ROOT);
        return m.contains("doesn't exist")
                || m.contains("does not exist")
                || m.contains("unknown table")
                || m.contains("table") && m.contains("not found");
    }
}
