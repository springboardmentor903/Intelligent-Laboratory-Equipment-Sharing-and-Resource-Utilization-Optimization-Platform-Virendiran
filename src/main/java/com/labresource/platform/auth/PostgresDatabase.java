package com.labresource.platform.auth;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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

    public PostgresDatabase(@Value("${spring.datasource.url}") String url,
                            @Value("${spring.datasource.username}") String username,
                            @Value("${spring.datasource.password}") String password) {
        this.url = url;
        this.username = username;
        this.password = password;
    }

    @PostConstruct
    void initialize() {
        try {
            Class.forName("org.postgresql.Driver");
            try (Connection connection = open(); Statement statement = connection.createStatement()) {
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS users (
                      id BIGSERIAL PRIMARY KEY,
                      name VARCHAR(80) NOT NULL,
                      email VARCHAR(254) NOT NULL UNIQUE,
                      password_hash TEXT NOT NULL,
                      password_salt TEXT NOT NULL,
                      is_approved BOOLEAN NOT NULL DEFAULT FALSE,
                      created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS instruments (
                      id BIGSERIAL PRIMARY KEY,
                      name VARCHAR(120) NOT NULL UNIQUE,
                      category VARCHAR(80) NOT NULL,
                      location VARCHAR(120) NOT NULL,
                      status VARCHAR(30) NOT NULL,
                      description TEXT NOT NULL
                    )
                    """);
                statement.execute("CREATE INDEX IF NOT EXISTS idx_users_pending ON users (is_approved, created_at)");
            }
            log.info("DATABASE_CONNECTED provider=PostgreSQL url={}", url);
            migrateLegacySqlite();
        } catch (ClassNotFoundException | SQLException exception) {
            log.error("DATABASE_STARTUP_FAILED provider=PostgreSQL url={} reason={}", url, exception.getMessage());
            throw new IllegalStateException("Could not initialize PostgreSQL. Check DB_URL, DB_USER, DB_PASSWORD, and that the database exists.", exception);
        }
    }

    public Connection open() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }

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
