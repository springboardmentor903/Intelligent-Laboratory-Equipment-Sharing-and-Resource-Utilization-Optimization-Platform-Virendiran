package com.labresource.platform.auth;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

@Component
public class PostgresDatabase {
    private static final Logger log = LoggerFactory.getLogger(PostgresDatabase.class);
    private final String url;
    private final String username;
    private final String password;
    private final HikariDataSource dataSource;

    public PostgresDatabase(@Value("${spring.datasource.url}") String url,
                            @Value("${spring.datasource.username}") String username,
                            @Value("${spring.datasource.password}") String password) {
        this.url = url;
        this.username = username;
        this.password = password;
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setPoolName("lab-resource-postgres");
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(5_000);
        config.setValidationTimeout(3_000);
        config.setConnectionInitSql("SET statement_timeout = '8s'");
        config.addDataSourceProperty("connectTimeout", 5);
        config.addDataSourceProperty("socketTimeout", 15);
        this.dataSource = new HikariDataSource(config);
    }

    @PostConstruct
    void initialize() {
        try {
            Class.forName("org.postgresql.Driver");
            try (Connection connection = open(); Statement statement = connection.createStatement()) {
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS institutions (
                      id BIGSERIAL PRIMARY KEY,
                      name VARCHAR(160) NOT NULL UNIQUE,
                      created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS users (
                      id BIGSERIAL PRIMARY KEY,
                      name VARCHAR(80) NOT NULL,
                      email VARCHAR(254) NOT NULL UNIQUE,
                      password_hash TEXT NOT NULL,
                      password_salt TEXT NOT NULL,
                      is_approved BOOLEAN NOT NULL DEFAULT FALSE,
                      created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      institution_id BIGINT REFERENCES institutions(id),
                      role VARCHAR(40) NOT NULL DEFAULT 'RESEARCHER'
                    )
                    """);
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS instruments (
                      id BIGSERIAL PRIMARY KEY,
                      name VARCHAR(120) NOT NULL UNIQUE,
                      category VARCHAR(80) NOT NULL,
                      location VARCHAR(120) NOT NULL,
                      status VARCHAR(30) NOT NULL,
                      description TEXT NOT NULL,
                      asset_tag VARCHAR(80),
                      owner_institution_id BIGINT REFERENCES institutions(id),
                      is_shareable BOOLEAN NOT NULL DEFAULT FALSE
                    )
                    """);
                statement.execute("CREATE INDEX IF NOT EXISTS idx_users_pending ON users (is_approved, created_at)");
                statement.execute("INSERT INTO institutions(name) VALUES ('Local Research Institution') ON CONFLICT (name) DO NOTHING");
                statement.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS institution_id BIGINT REFERENCES institutions(id)");
                statement.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS role VARCHAR(40) NOT NULL DEFAULT 'RESEARCHER'");
                statement.execute("ALTER TABLE instruments ADD COLUMN IF NOT EXISTS asset_tag VARCHAR(80)");
                statement.execute("ALTER TABLE instruments ADD COLUMN IF NOT EXISTS owner_institution_id BIGINT REFERENCES institutions(id)");
                statement.execute("ALTER TABLE instruments ADD COLUMN IF NOT EXISTS is_shareable BOOLEAN NOT NULL DEFAULT FALSE");
                statement.execute("UPDATE instruments SET asset_tag = 'LAB-' || LPAD(id::text, 5, '0') WHERE asset_tag IS NULL");
                statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_instruments_asset_tag ON instruments (asset_tag) WHERE asset_tag IS NOT NULL");
                statement.execute("UPDATE instruments SET owner_institution_id=(SELECT id FROM institutions WHERE name='Local Research Institution') WHERE owner_institution_id IS NULL");
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS sharing_listings (
                      id BIGSERIAL PRIMARY KEY,
                      equipment_id BIGINT NOT NULL UNIQUE REFERENCES instruments(id) ON DELETE CASCADE,
                      fee_per_hour NUMERIC(12,2) NOT NULL CHECK (fee_per_hour >= 0),
                      external_share_percentage NUMERIC(5,2) NOT NULL CHECK (external_share_percentage >= 0 AND external_share_percentage <= 100),
                      minimum_notice_hours INTEGER NOT NULL CHECK (minimum_notice_hours >= 0),
                      maximum_booking_hours INTEGER NOT NULL CHECK (maximum_booking_hours > 0),
                      sharing_terms VARCHAR(2000) NOT NULL,
                      is_active BOOLEAN NOT NULL DEFAULT TRUE,
                      created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS sharing_requests (
                      id BIGSERIAL PRIMARY KEY,
                      equipment_id BIGINT NOT NULL REFERENCES instruments(id),
                      requester_id BIGINT NOT NULL REFERENCES users(id),
                      requester_institution_id BIGINT NOT NULL REFERENCES institutions(id),
                      purpose VARCHAR(500) NOT NULL CHECK (length(trim(purpose)) > 0),
                      start_date DATE NOT NULL,
                      end_date DATE NOT NULL,
                      status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','FORWARDED','APPROVED','REJECTED','WITHDRAWN')),
                      requester_reviewed_by BIGINT REFERENCES users(id),
                      requester_reviewed_at TIMESTAMPTZ,
                      requester_review_remarks VARCHAR(1000),
                      reviewed_by BIGINT REFERENCES users(id),
                      reviewed_at TIMESTAMPTZ,
                      review_remarks VARCHAR(1000),
                      created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      CHECK (end_date >= start_date)
                    )
                    """);
                statement.execute("CREATE INDEX IF NOT EXISTS idx_sharing_requests_requester ON sharing_requests(requester_id, created_at DESC)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_sharing_requests_route ON sharing_requests(requester_institution_id, status, created_at DESC)");
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS sharing_agreements (
                      id BIGSERIAL PRIMARY KEY,
                      request_id BIGINT NOT NULL UNIQUE REFERENCES sharing_requests(id),
                      equipment_id BIGINT NOT NULL REFERENCES instruments(id),
                      owner_institution_id BIGINT NOT NULL REFERENCES institutions(id),
                      partner_institution_id BIGINT NOT NULL REFERENCES institutions(id),
                      start_date DATE NOT NULL,
                      end_date DATE NOT NULL,
                      allowed_start TIME,
                      allowed_end TIME,
                      fee_per_hour NUMERIC(12,2) NOT NULL CHECK (fee_per_hour >= 0),
                      status VARCHAR(12) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REVOKED')),
                      approved_by BIGINT NOT NULL REFERENCES users(id),
                      approved_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      revoked_by BIGINT REFERENCES users(id),
                      revoked_at TIMESTAMPTZ,
                      revocation_reason VARCHAR(1000),
                      CHECK (end_date >= start_date),
                      CHECK ((allowed_start IS NULL AND allowed_end IS NULL) OR (allowed_start IS NOT NULL AND allowed_end IS NOT NULL AND allowed_end > allowed_start))
                    )
                    """);
                statement.execute("CREATE INDEX IF NOT EXISTS idx_sharing_agreements_owner ON sharing_agreements(owner_institution_id, status, end_date)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_sharing_agreements_partner ON sharing_agreements(partner_institution_id, status, end_date)");
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS access_grants (
                      id BIGSERIAL PRIMARY KEY,
                      agreement_id BIGINT NOT NULL REFERENCES sharing_agreements(id),
                      user_id BIGINT NOT NULL REFERENCES users(id),
                      granted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      revoked_by BIGINT REFERENCES users(id),
                      revoked_at TIMESTAMPTZ,
                      revocation_reason VARCHAR(1000),
                      UNIQUE(agreement_id,user_id)
                    )
                    """);
                statement.execute("CREATE INDEX IF NOT EXISTS idx_access_grants_user ON access_grants(user_id, agreement_id, revoked_at)");
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS bookings (
                      id BIGSERIAL PRIMARY KEY,
                      instrument_id BIGINT NOT NULL REFERENCES instruments(id),
                      agreement_id BIGINT REFERENCES sharing_agreements(id),
                      user_id BIGINT NOT NULL REFERENCES users(id),
                      start_time TIMESTAMPTZ NOT NULL,
                      end_time TIMESTAMPTZ NOT NULL,
                      purpose VARCHAR(1000) NOT NULL,
                      status VARCHAR(30) NOT NULL,
                      approved_by BIGINT REFERENCES users(id),
                      approved_at TIMESTAMPTZ,
                      rejection_reason VARCHAR(1000),
                      cancelled_at TIMESTAMPTZ,
                      cancellation_reason VARCHAR(1000),
                      created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      CHECK (end_time > start_time)
                    )
                    """);
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS booking_history (
                      id BIGSERIAL PRIMARY KEY,
                      booking_id BIGINT NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
                      previous_status VARCHAR(30),
                      new_status VARCHAR(30) NOT NULL,
                      changed_by BIGINT REFERENCES users(id),
                      changed_by_name VARCHAR(254) NOT NULL,
                      changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      remarks VARCHAR(1000)
                    )
                    """);
                statement.execute("CREATE INDEX IF NOT EXISTS idx_bookings_conflict ON bookings (instrument_id, start_time, end_time) WHERE status IN ('PENDING_APPROVAL','CONFIRMED','IN_USE')");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_bookings_user ON bookings (user_id, start_time DESC)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_booking_history ON booking_history (booking_id, changed_at)");
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS booking_holds (
                      id BIGSERIAL PRIMARY KEY,
                      instrument_id BIGINT NOT NULL REFERENCES instruments(id),
                      user_id BIGINT NOT NULL REFERENCES users(id),
                      agreement_id BIGINT REFERENCES sharing_agreements(id),
                      start_time TIMESTAMPTZ NOT NULL,
                      end_time TIMESTAMPTZ NOT NULL,
                      expires_at TIMESTAMPTZ NOT NULL,
                      CHECK (end_time > start_time)
                    )
                    """);
                statement.execute("CREATE INDEX IF NOT EXISTS idx_booking_holds_active ON booking_holds(instrument_id,start_time,end_time,expires_at)");
                statement.execute("ALTER TABLE bookings ADD COLUMN IF NOT EXISTS approved_by_name VARCHAR(254)");
                statement.execute("ALTER TABLE bookings ADD COLUMN IF NOT EXISTS checked_in_at TIMESTAMPTZ");
                statement.execute("ALTER TABLE bookings ADD COLUMN IF NOT EXISTS checked_out_at TIMESTAMPTZ");
                statement.execute("ALTER TABLE instruments ADD COLUMN IF NOT EXISTS asset_tag VARCHAR(80)");
                statement.execute("ALTER TABLE bookings ADD COLUMN IF NOT EXISTS agreement_id BIGINT REFERENCES sharing_agreements(id)");
                statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_instruments_asset_tag ON instruments (asset_tag) WHERE asset_tag IS NOT NULL");
                statement.execute("UPDATE instruments SET asset_tag = 'LAB-' || LPAD(id::text, 5, '0') WHERE asset_tag IS NULL");
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS usage_logs (
                      id BIGSERIAL PRIMARY KEY,
                      instrument_id BIGINT NOT NULL REFERENCES instruments(id),
                      user_id BIGINT NOT NULL REFERENCES users(id),
                      booking_id BIGINT NOT NULL REFERENCES bookings(id),
                      actual_start_time TIMESTAMPTZ NOT NULL,
                      actual_end_time TIMESTAMPTZ,
                      usage_type VARCHAR(40) NOT NULL DEFAULT 'BOOKING',
                      remarks VARCHAR(1000),
                      created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      CHECK (actual_end_time IS NULL OR actual_end_time >= actual_start_time)
                    )
                    """);
                statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_usage_logs_open_booking ON usage_logs (booking_id) WHERE actual_end_time IS NULL");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_usage_logs_open_equipment ON usage_logs (instrument_id, actual_start_time) WHERE actual_end_time IS NULL");
                statement.execute("UPDATE instruments SET status='AVAILABLE' WHERE LOWER(status)='available'");
                statement.execute("UPDATE instruments SET status='UNDER_MAINTENANCE' WHERE LOWER(status) IN ('maintenance','under maintenance','under_maintenance')");
                statement.execute("UPDATE instruments SET status='OUT_OF_SERVICE' WHERE LOWER(status) IN ('out of service','out_of_service')");
                statement.execute("UPDATE instruments SET status='RETIRED' WHERE LOWER(status)='retired'");
                statement.execute("UPDATE instruments SET status='AVAILABLE' WHERE LOWER(status)='in use' AND NOT EXISTS (SELECT 1 FROM usage_logs ul WHERE ul.instrument_id=instruments.id AND ul.actual_end_time IS NULL)");
            }
            log.info("DATABASE_CONNECTED provider=PostgreSQL url={}", url);
            migrateLegacySqlite();
        } catch (ClassNotFoundException | SQLException exception) {
            log.error("DATABASE_STARTUP_FAILED provider=PostgreSQL url={} reason={}", url, exception.getMessage());
            throw new IllegalStateException("Could not initialize PostgreSQL. Check DB_URL, DB_USER, DB_PASSWORD, and that the database exists.", exception);
        }
    }

    public Connection open() throws SQLException {
        return dataSource.getConnection();
    }

    @PreDestroy
    void closePool() { dataSource.close(); }

    private void migrateLegacySqlite() {
        String legacyPath = System.getenv().getOrDefault("DB_PATH", "lab-resource.sqlite");
        if (!Files.isRegularFile(Path.of(legacyPath))) return;
        int migrated = 0;
        try {
            Class.forName("org.sqlite.JDBC");
            try (Connection sqlite = DriverManager.getConnection("jdbc:sqlite:" + legacyPath);
                 Statement metadata = sqlite.createStatement();
                 ResultSet columns = metadata.executeQuery("PRAGMA table_info(users)")) {
                boolean hasApproval = false;
                boolean hasCreatedAt = false;
                while (columns.next()) {
                    hasApproval |= "is_approved".equals(columns.getString("name"));
                    hasCreatedAt |= "created_at".equals(columns.getString("name"));
                }
                String select = "SELECT name, email, password_hash, password_salt" +
                    (hasApproval ? ", is_approved" : "") + (hasCreatedAt ? ", created_at" : "") + " FROM users";
                try (Statement oldUsers = sqlite.createStatement(); ResultSet rows = oldUsers.executeQuery(select);
                     Connection postgres = open();
                     PreparedStatement insert = postgres.prepareStatement("""
                         INSERT INTO users (name, email, password_hash, password_salt, is_approved, created_at)
                         VALUES (?, ?, ?, ?, ?, COALESCE(?::timestamptz, CURRENT_TIMESTAMP))
                         ON CONFLICT (email) DO NOTHING
                         """)) {
                    while (rows.next()) {
                        insert.setString(1, rows.getString("name"));
                        insert.setString(2, rows.getString("email").trim().toLowerCase(java.util.Locale.ROOT));
                        insert.setString(3, rows.getString("password_hash"));
                        insert.setString(4, rows.getString("password_salt"));
                        insert.setBoolean(5, hasApproval && rows.getBoolean("is_approved"));
                        insert.setString(6, hasCreatedAt ? rows.getString("created_at") : null);
                        migrated += insert.executeUpdate();
                    }
                }
            }
            log.info("SQLITE_MIGRATION_FINISHED source={} users_migrated={}", legacyPath, migrated);
        } catch (ClassNotFoundException | SQLException exception) {
            log.error("SQLITE_MIGRATION_FAILED source={} reason={}", legacyPath, exception.getMessage());
            throw new IllegalStateException("Could not migrate existing SQLite user accounts into PostgreSQL.", exception);
        }
    }
}
