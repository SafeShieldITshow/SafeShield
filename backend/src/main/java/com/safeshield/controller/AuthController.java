package com.safeshield.controller;

import com.safeshield.dto.LoginRequest;
import com.safeshield.dto.SignupRequest;
import com.safeshield.model.User;
import com.safeshield.service.AuthService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/signup")
    public Map<String, String> signup(@RequestBody SignupRequest req) {
        return authService.signup(req);
    }

    @PostMapping("/login")
    public Map<String, String> login(@RequestBody LoginRequest req) {
        return authService.login(req);
    }

    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal User user) {
        return Map.of("id", user.getId(), "username", user.getUsername(),
                "name", user.getName(), "email", user.getEmail());
    }
}
