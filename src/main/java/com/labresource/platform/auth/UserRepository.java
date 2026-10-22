package com.labresource.platform.auth;

import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class UserRepository {
    private final PostgresDatabase database;

    public UserRepository(PostgresDatabase database) { this.database = database; }

    public Optional<StoredUser> findByEmail(String email) {
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT id, name, email, password_hash, password_salt, is_approved FROM users WHERE email = ?")) {
            statement.setString(1, email);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(new StoredUser(
                    new User(result.getLong("id"), result.getString("name"), result.getString("email")),
                    result.getString("password_hash"), result.getString("password_salt"), result.getBoolean("is_approved")));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not look up the user in PostgreSQL.", exception);
        }
    }

    public User insert(String name, String email, String passwordHash, String salt) throws SQLException {
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement(
                 "INSERT INTO users (name, email, password_hash, password_salt) VALUES (?, ?, ?, ?) RETURNING id")) {
            statement.setString(1, name);
            statement.setString(2, email);
            statement.setString(3, passwordHash);
            statement.setString(4, salt);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("PostgreSQL did not return the inserted user id.");
                return new User(result.getLong(1), name, email);
            }
        }
    }

    public List<PendingUser> findPendingUsers() {
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT id, name, email, created_at FROM users WHERE is_approved = FALSE ORDER BY created_at, id");
             ResultSet rows = statement.executeQuery()) {
            var pending = new ArrayList<PendingUser>();
            while (rows.next()) pending.add(new PendingUser(rows.getLong("id"), rows.getString("name"),
                rows.getString("email"), rows.getString("created_at")));
            return pending;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not load pending accounts from PostgreSQL.", exception);
        }
    }

    public Optional<PendingUser> approve(long id) {
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement("""
                 UPDATE users SET is_approved = TRUE WHERE id = ? AND is_approved = FALSE
                 RETURNING id, name, email, created_at
                 """)) {
            statement.setLong(1, id);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                return Optional.of(new PendingUser(row.getLong("id"), row.getString("name"),
                    row.getString("email"), row.getString("created_at")));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not approve the account in PostgreSQL.", exception);
        }
    }

    public record StoredUser(User user, String passwordHash, String salt, boolean approved) { }
    public record PendingUser(long id, String name, String email, String createdAt) { }
}
