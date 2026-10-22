package com.labresource.platform.auth;

import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final String SESSION_USER = "authenticatedUser";
    private final AuthService authService;

    public AuthController(AuthService authService) { this.authService = authService; }

    @PostMapping("/register")
    public ResponseEntity<AuthRequests.RegistrationResponse> register(@RequestBody(required = false) AuthRequests.Register request) {
        User user = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(new AuthRequests.RegistrationResponse(
            user, "pending", "Your account was created and is waiting for admin approval."));
    }

    @PostMapping("/login")
    public AuthRequests.UserResponse login(@RequestBody(required = false) AuthRequests.Login request, HttpSession session) {
        User user = authService.login(request);
        session.setAttribute(SESSION_USER, user);
        return new AuthRequests.UserResponse(user);
    }

    @GetMapping("/me")
    public ResponseEntity<AuthRequests.UserResponse> me(HttpSession session) {
        Object current = session.getAttribute(SESSION_USER);
        if (current instanceof User user) return ResponseEntity.ok(new AuthRequests.UserResponse(user));
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpSession session) {
        Object current = session.getAttribute(SESSION_USER);
        if (current instanceof User user) log.info("LOGOUT_SUCCEEDED user_id={} email={}", user.id(), user.email());
        session.invalidate();
        return ResponseEntity.noContent().build();
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(AuthService.AuthException.class)
    public ResponseEntity<AuthRequests.ErrorResponse> authError(AuthService.AuthException exception) {
        return ResponseEntity.status(exception.status()).body(new AuthRequests.ErrorResponse(exception.getMessage()));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<AuthRequests.ErrorResponse> malformedBody() {
        return ResponseEntity.badRequest().body(new AuthRequests.ErrorResponse("Request body must contain valid JSON."));
    }
}
