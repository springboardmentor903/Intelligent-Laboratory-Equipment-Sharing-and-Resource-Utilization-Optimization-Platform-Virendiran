package com.labresource.platform.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private final UserRepository users;
    private final PasswordHasher passwordHasher;

    public AuthService(UserRepository users, PasswordHasher passwordHasher) {
        this.users = users;
        this.passwordHasher = passwordHasher;
    }

    public User register(AuthRequests.Register request) {
        String name = request == null || request.getName() == null ? "" : request.getName().trim();
        String email = request == null || request.getEmail() == null ? "" : request.getEmail().trim().toLowerCase(Locale.ROOT);
        String password = request == null || request.getPassword() == null ? "" : request.getPassword();
        Long institutionId = request == null ? null : request.getInstitutionId();
        String role = request == null || request.getRole() == null || request.getRole().isBlank()
            ? "RESEARCHER" : request.getRole().trim().toUpperCase(Locale.ROOT);
        if (name.length() < 2 || name.length() > 80) throw new AuthException(400, "Enter a name between 2 and 80 characters.");
        if (!EMAIL.matcher(email).matches() || email.length() > 254) throw new AuthException(400, "Enter a valid email address.");
        if (password.length() < 8 || password.length() > 128) throw new AuthException(400, "Password must be at least 8 characters.");
        if (!java.util.Set.of("RESEARCHER", "STUDENT", "DEPARTMENT_HEAD").contains(role)) throw new AuthException(400, "Choose Researcher, Student, or Department Head.");
        if (institutionId != null && (institutionId < 1 || !users.institutionExists(institutionId))) throw new AuthException(400, "Choose a valid institution.");
        if ("DEPARTMENT_HEAD".equals(role) && institutionId == null) throw new AuthException(400, "A Department Head account must belong to an institution.");
        if (users.findByEmail(email).isPresent()) throw new AuthException(409, "An account with that email already exists.");
        String salt = passwordHasher.newSalt();
        try {
            User created = users.insert(name, email, passwordHasher.hash(password, salt), salt, institutionId, role);
            log.info("NEW_USER_DETECTED user_id={} name=\"{}\" email={} approval=PENDING database=PostgreSQL", created.id(), created.name(), created.email());
            return created;
        } catch (SQLException exception) {
            if ("23505".equals(exception.getSQLState())) throw new AuthException(409, "An account with that email already exists.");
            log.error("USER_REGISTRATION_FAILED email={} reason={}", email, exception.getMessage());
            throw new IllegalStateException("Could not create the account.", exception);
        }
    }

    public User login(AuthRequests.Login request) {
        String email = request == null || request.getEmail() == null ? "" : request.getEmail().trim().toLowerCase(Locale.ROOT);
        String password = request == null || request.getPassword() == null ? "" : request.getPassword();
        var stored = users.findByEmail(email);
        if (stored.isEmpty() || password.isEmpty() || password.length() > 128) {
            log.warn("LOGIN_REJECTED email={} reason=INVALID_CREDENTIALS", email);
            throw new AuthException(401, "Email or password is incorrect.");
        }
        String candidate = passwordHasher.hash(password, stored.get().salt());
        boolean matches = MessageDigest.isEqual(candidate.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            stored.get().passwordHash().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (!matches) {
            log.warn("LOGIN_REJECTED email={} reason=INVALID_CREDENTIALS", email);
            throw new AuthException(401, "Email or password is incorrect.");
        }
        if (!stored.get().approved()) {
            log.info("LOGIN_BLOCKED user_id={} email={} reason=ADMIN_APPROVAL_PENDING", stored.get().user().id(), email);
            throw new AuthException(403, "Your account is waiting for admin approval.");
        }
        log.info("LOGIN_SUCCEEDED user_id={} email={}", stored.get().user().id(), email);
        return stored.get().user();
    }

    public java.util.List<UserRepository.PendingUser> pendingUsers() {
        var pending = users.findPendingUsers();
        log.info("ADMIN_PENDING_ACCOUNTS_LOADED count={}", pending.size());
        return pending;
    }
    public java.util.Optional<UserRepository.PendingUser> approve(long id) {
        var approved = users.approve(id);
        approved.ifPresent(user -> log.info("ADMIN_APPROVED_USER user_id={} name=\"{}\" email={}", user.id(), user.name(), user.email()));
        return approved;
    }

    public static class AuthException extends RuntimeException {
        private final int status;
        public AuthException(int status, String message) { super(message); this.status = status; }
        public int status() { return status; }
    }
}
