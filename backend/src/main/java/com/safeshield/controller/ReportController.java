package com.safeshield.controller;

import com.safeshield.dto.ReportGenerateRequest;
import com.safeshield.model.User;
import com.safeshield.service.ReportService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @PostMapping("/generate")
    public Map<String, Object> generate(@RequestBody ReportGenerateRequest req,
                                         @AuthenticationPrincipal User user) {
        return reportService.generate(user, req.sessionId(), req.title());
    }

    @GetMapping
    public List<Map<String, Object>> list(@AuthenticationPrincipal User user) {
        return reportService.list(user);
    }

    @GetMapping("/latest")
    public Map<String, Object> latest(@AuthenticationPrincipal User user) {
        return reportService.latest(user);
    }

    @GetMapping("/session/{sessionId}/latest")
    public Map<String, Object> latestForSession(@PathVariable Long sessionId,
                                                @AuthenticationPrincipal User user) {
        return reportService.latestForSession(user, sessionId);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return reportService.get(user, id);
    }

    @DeleteMapping("/{id}")
    public Map<String, Boolean> delete(@PathVariable Long id, @AuthenticationPrincipal User user) {
        reportService.delete(user, id);
        return Map.of("ok", true);
    }
}
