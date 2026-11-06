package com.labresource.platform.auth;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/institutions")
public class InstitutionController {
    private final UserRepository users;
    public InstitutionController(UserRepository users) { this.users=users; }
    @GetMapping public List<Institution> list() { return users.findInstitutions(); }
}
