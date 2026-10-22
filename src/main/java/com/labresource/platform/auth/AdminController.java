package com.labresource.platform.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpSession;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final AuthService authService;
    private static final String SESSION_ADMIN = "authenticatedAdmin";
    private final String adminEmail = System.getenv("ADMIN_EMAIL");
    private final String adminPassword = System.getenv("ADMIN_PASSWORD");

    public AdminController(AuthService authService) { this.authService = authService; }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody(required = false) AuthRequests.AdminLogin request, HttpSession session) {
        String email = request == null || request.getEmail() == null ? "" : request.getEmail().trim();
        String password = request == null || request.getPassword() == null ? "" : request.getPassword();
        if (adminEmail == null || adminEmail.isBlank() || adminPassword == null || adminPassword.isBlank()
            || !constantTimeEquals(adminEmail.trim(), email) || !constantTimeEquals(adminPassword, password)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Admin email or password is incorrect, or admin login is not configured."));
        }
        session.setAttribute(SESSION_ADMIN, true);
        return ResponseEntity.ok(Map.of("message", "Admin signed in."));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpSession session) {
        session.removeAttribute(SESSION_ADMIN);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/session")
    public ResponseEntity<?> session(HttpSession session) {
        return authorized(session) ? ResponseEntity.ok(Map.of("authenticated", true))
            : ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("authenticated", false));
    }

    @GetMapping("/pending")
    public ResponseEntity<?> pending(HttpSession session) {
        if (!authorized(session)) return unauthorized();
        return ResponseEntity.ok(authService.pendingUsers());
    }

    @PostMapping("/users/{id}/approve")
    public ResponseEntity<?> approve(@PathVariable long id,
                                     HttpSession session) {
        if (!authorized(session)) return unauthorized();
        var approved = id < 1 ? java.util.Optional.<UserRepository.PendingUser>empty() : authService.approve(id);
        if (approved.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Pending account not found."));
        }
        return ResponseEntity.ok(Map.of("message", "Account approved.", "user", approved.get()));
    }

    private boolean authorized(HttpSession session) {
        return Boolean.TRUE.equals(session.getAttribute(SESSION_ADMIN));
    }

    private boolean constantTimeEquals(String expected, String supplied) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }

    private ResponseEntity<AuthRequests.ErrorResponse> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(new AuthRequests.ErrorResponse("Admin session is required. Sign in with the configured admin credentials."));
    }
}
