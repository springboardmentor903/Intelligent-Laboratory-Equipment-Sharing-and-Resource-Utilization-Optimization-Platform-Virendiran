package com.labresource.platform.auth;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/instruments")
public class InstrumentController {
    private final InstrumentRepository instruments;

    public InstrumentController(InstrumentRepository instruments) { this.instruments = instruments; }

    @GetMapping
    public ResponseEntity<List<Instrument>> list(HttpSession session) {
        if (!(session.getAttribute("authenticatedUser") instanceof User)
            && !Boolean.TRUE.equals(session.getAttribute("authenticatedAdmin"))) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(instruments.findAll());
    }
}
