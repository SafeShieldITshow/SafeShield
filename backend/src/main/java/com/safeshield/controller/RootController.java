package com.safeshield.controller;

import com.safeshield.service.ChatService;
import com.safeshield.service.LawApiService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

import java.util.Map;

@RestController
public class RootController {

    private final ChatService chatService;
    private final LawApiService lawApiService;

    public RootController(ChatService chatService, LawApiService lawApiService) {
        this.chatService = chatService;
        this.lawApiService = lawApiService;
    }

    @GetMapping("/")
    public RedirectView root() {
        return new RedirectView("http://127.0.0.1:5173/");
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "ok", true,
                "backend", "running",
                "frontend", "http://127.0.0.1:5173/",
                "ai", chatService.getProviderStatus(),
                "law_data", lawApiService.getStatus()
        );
    }
}
