package com.matterworks.core.database.dao;

import com.matterworks.core.database.DatabaseManager;
import com.matterworks.core.domain.shop.VoidShopItem;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class VoidShopDAO {

    private final DatabaseManager db;

    public VoidShopDAO(DatabaseManager db) {
        this.db = db;
    }

    public List<VoidShopItem> loadAll() {
        String sql = """
                SELECT item_id, display_name, void_price, description, sort_order
                FROM void_shop_catalog
                ORDER BY sort_order ASC, item_id ASC
                """;

        List<VoidShopItem> out = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) out.add(map(rs));

        } catch (SQLException e) {
            e.printStackTrace();
        }
        return out;
    }

    public VoidShopItem loadById(String itemId) {
        if (itemId == null || itemId.isBlank()) return null;

        String sql = """
                SELECT item_id, display_name, void_price, description, sort_order
                FROM void_shop_catalog
                WHERE item_id = ?
                LIMIT 1
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return map(rs);
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    private static VoidShopItem map(ResultSet rs) throws SQLException {
        return new VoidShopItem(
                rs.getString("item_id"),
                rs.getString("display_name"),
                rs.getInt("void_price"),
                rs.getString("description"),
                rs.getInt("sort_order")
        );
    }
}
