package com.safeshield.controller;

import com.safeshield.service.ChatService;
import com.safeshield.service.LawApiService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

import java.util.Map;

@RestController
public class RootController {

    private final ChatService chatService;
    private final LawApiService lawApiService;
    private final String frontendUrl;

    public RootController(ChatService chatService,
                          LawApiService lawApiService,
                          @Value("${app.frontend-url}") String frontendUrl) {
        this.chatService = chatService;
        this.lawApiService = lawApiService;
        this.frontendUrl = stripTrailingSlash(frontendUrl);
    }

    @GetMapping("/")
    public RedirectView root() {
        return new RedirectView(frontendUrl + "/");
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "ok", true,
                "backend", "running",
                "frontend", frontendUrl,
                "ai", chatService.getProviderStatus(),
                "law_data", lawApiService.getStatus()
        );
    }

    private String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) return "http://localhost:5173";
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
