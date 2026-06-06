package com.safeshield.controller;

import com.safeshield.model.User;
import com.safeshield.repository.ReportRepository;
import com.safeshield.repository.SessionRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/users")
public class UserController {

    private final SessionRepository sessionRepository;
    private final ReportRepository reportRepository;

    public UserController(SessionRepository sessionRepository, ReportRepository reportRepository) {
        this.sessionRepository = sessionRepository;
        this.reportRepository = reportRepository;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats(@AuthenticationPrincipal User user) {
        long sessions = sessionRepository.countByUser(user);
        long reports = reportRepository.countByUser(user);
        return Map.of(
                "username", user.getUsername(),
                "name", user.getName(),
                "email", user.getEmail(),
                "sessions_count", sessions,
                "reports_count", reports
        );
    }
}
