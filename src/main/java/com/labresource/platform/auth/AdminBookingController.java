package com.labresource.platform.auth;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/bookings")
public class AdminBookingController {
    private final BookingService bookings;
    public AdminBookingController(BookingService bookings) { this.bookings = bookings; }

    @GetMapping("/pending")
    public ResponseEntity<?> pending(HttpSession session) {
        if (!isAdmin(session)) return denied(session);
        return ResponseEntity.ok(bookings.pendingApprovals());
    }

    @GetMapping("/current-use")
    public ResponseEntity<?> currentUse(HttpSession session) {
        if (!isAdmin(session)) return denied(session);
        return ResponseEntity.ok(bookings.currentEquipmentUse());
    }

    private boolean isAdmin(HttpSession session) { return Boolean.TRUE.equals(session.getAttribute("authenticatedAdmin")); }
    private ResponseEntity<Map<String,String>> denied(HttpSession session) {
        boolean signedIn = session.getAttribute("authenticatedUser") instanceof User;
        return ResponseEntity.status(signedIn ? 403 : 401).body(Map.of("error", signedIn ? "Admin access is required." : "Admin sign-in is required."));
    }
}
