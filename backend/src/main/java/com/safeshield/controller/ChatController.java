package com.safeshield.controller;

import com.safeshield.dto.MessageRequest;
import com.safeshield.model.User;
import com.safeshield.service.ChatService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/sessions")
    public Map<String, Object> createSession(@AuthenticationPrincipal User user) {
        var s = chatService.createSession(user);
        return Map.of("session_id", s.getId());
    }

    @GetMapping("/sessions")
    public List<Map<String, Object>> getSessions(@AuthenticationPrincipal User user) {
        return chatService.getSessions(user);
    }

    @GetMapping("/sessions/{id}/messages")
    public List<Map<String, Object>> getMessages(@PathVariable Long id,
                                                  @AuthenticationPrincipal User user) {
        return chatService.getMessages(id, user);
    }

    @GetMapping("/sessions/{id}")
    public Map<String, Object> getSessionDetail(@PathVariable Long id,
                                                @AuthenticationPrincipal User user) {
        return chatService.getSessionDetail(id, user);
    }

    @GetMapping("/sessions/{id}/readiness")
    public Map<String, Object> getReadiness(@PathVariable Long id,
                                            @AuthenticationPrincipal User user) {
        return chatService.getReadiness(id, user);
    }

    @PostMapping("/message")
    public Map<String, Object> sendMessage(@RequestBody MessageRequest req,
                                            @AuthenticationPrincipal User user) {
        return chatService.sendMessage(user, req.sessionId(), req.content());
    }
}
