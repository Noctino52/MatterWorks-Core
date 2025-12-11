package com.matterworks.core.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * Manages the connection to the 'matterworks_core' database.
 * Responsible for initializing the Core Schema tables.
 */
public class DatabaseManager {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseManager.class);
    private HikariDataSource dataSource;

    public void connect() {
        Properties props = loadProperties();

        HikariConfig config = new HikariConfig();
        // Construct JDBC URL for MariaDB
        String jdbcUrl = "jdbc:mariadb://" + props.getProperty("db.host") + ":" +
                props.getProperty("db.port") + "/" +
                props.getProperty("db.name");

        config.setJdbcUrl(jdbcUrl);
        config.setUsername(props.getProperty("db.user"));
        config.setPassword(props.getProperty("db.password"));

        // Optimize for performance
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");

        this.dataSource = new HikariDataSource(config);
        logger.info("Connected to matterworks_core database.");

        // Initialize Core Tables immediately
        initTables();
    }

    private Properties loadProperties() {
        Properties props = new Properties();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("database.properties")) {
            if (input == null) throw new RuntimeException("database.properties not found in resources");
            props.load(input);
        } catch (IOException ex) {
            throw new RuntimeException("Error loading DB config", ex);
        }
        return props;
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public void close() {
        if (dataSource != null) dataSource.close();
    }

    /**
     * Creates the tables defined in the ER Diagram (Core Sections only).
     */
    private void initTables() {
        // Order matters for Foreign Keys!
        String[] queries = {
                // 1. GLOBAL STATE (Singleton)
                """
            CREATE TABLE IF NOT EXISTS server_gamestate (
                id INT PRIMARY KEY DEFAULT 1,
                active_faction_id VARCHAR(32) NOT NULL DEFAULT 'KWEEBEC',
                current_cycle_end TIMESTAMP NOT NULL,
                global_multipliers JSON,
                CONSTRAINT check_singleton CHECK (id = 1)
            );
            """,
                // Ensure the singleton row exists
                "INSERT IGNORE INTO server_gamestate (id, current_cycle_end) VALUES (1, NOW());",

                // 2. STATIC DEFINITIONS (Item & Tech Catalogs)
                """
            CREATE TABLE IF NOT EXISTS item_definitions (
                id VARCHAR(64) PRIMARY KEY,
                category VARCHAR(32) NOT NULL,
                base_price DECIMAL(30, 4) NOT NULL,
                tier INT DEFAULT 1,
                stats JSON,
                model_id VARCHAR(64)
            );
            """,
                """
            CREATE TABLE IF NOT EXISTS tech_definitions (
                node_id VARCHAR(64) PRIMARY KEY,
                name_display VARCHAR(64),
                cost_money DECIMAL(30, 4) NOT NULL,
                parent_node_ids JSON,
                unlock_machine_ids JSON
            );
            """,

                // 3. PLAYERS (The central entity)
                """
            CREATE TABLE IF NOT EXISTS players (
                uuid BINARY(16) PRIMARY KEY,
                username VARCHAR(32) NOT NULL,
                money DECIMAL(30, 4) DEFAULT 0.0000,
                void_coins INT DEFAULT 0,
                prestige_level INT DEFAULT 0,
                active_boosters JSON,
                purchased_cap_bonus INT DEFAULT 0,
                tech_unlocks JSON,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                last_login TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            );
            """,

                // 4. DEPENDENT TABLES (Inventory, Codes, Plots)
                """
            CREATE TABLE IF NOT EXISTS player_inventory (
                player_uuid BINARY(16) NOT NULL,
                item_id VARCHAR(64) NOT NULL,
                quantity INT DEFAULT 0,
                PRIMARY KEY (player_uuid, item_id),
                CONSTRAINT fk_inv_player FOREIGN KEY (player_uuid) REFERENCES players(uuid) ON DELETE CASCADE,
                CONSTRAINT fk_inv_item FOREIGN KEY (item_id) REFERENCES item_definitions(id)
            );
            """,
                // Bridge Table (Written by Core, Read by Web)
                """
            CREATE TABLE IF NOT EXISTS verification_codes (
                code VARCHAR(8) PRIMARY KEY,
                player_uuid BINARY(16) NOT NULL,
                expires_at TIMESTAMP NOT NULL,
                CONSTRAINT fk_verify_player FOREIGN KEY (player_uuid) REFERENCES players(uuid) ON DELETE CASCADE
            );
            """,
                """
            CREATE TABLE IF NOT EXISTS plots (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                owner_id BINARY(16) NOT NULL,
                allocation_index INT NOT NULL,
                world_x INT NOT NULL,
                world_z INT NOT NULL,
                expansion_tier INT DEFAULT 1,
                is_active BOOLEAN DEFAULT TRUE,
                CONSTRAINT fk_plot_owner FOREIGN KEY (owner_id) REFERENCES players(uuid)
            );
            """,
                // 5. DEEP DEPENDENCIES (Machines inside Plots, Logs)
                """
            CREATE TABLE IF NOT EXISTS plot_objects (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                plot_id BIGINT NOT NULL,
                x INT NOT NULL,
                y INT NOT NULL,
                z INT NOT NULL,
                type_id VARCHAR(64) NOT NULL,
                meta_data JSON,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_chunk (plot_id),
                CONSTRAINT fk_obj_plot FOREIGN KEY (plot_id) REFERENCES plots(id) ON DELETE CASCADE,
                CONSTRAINT fk_obj_type FOREIGN KEY (type_id) REFERENCES item_definitions(id)
            );
            """,
                """
            CREATE TABLE IF NOT EXISTS transactions (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                player_uuid BINARY(16) NOT NULL,
                action_type VARCHAR(32) NOT NULL,
                currency VARCHAR(16) NOT NULL,
                amount DECIMAL(30, 4) NOT NULL,
                item_id VARCHAR(64),
                occurred_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_tx_player FOREIGN KEY (player_uuid) REFERENCES players(uuid)
            );
            """
        };

        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            for (String sql : queries) {
                stmt.execute(sql);
            }
            logger.info("MatterWorks Core tables verified/created successfully.");
        } catch (SQLException e) {
            logger.error("Database initialization failed", e);
            throw new RuntimeException("Init failed", e);
        }
    }
}