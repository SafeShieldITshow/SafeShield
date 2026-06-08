package com.safeshield.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.List;

public record MessageRequest(
        @JsonAlias("session_id") Long sessionId,
        String content,
        List<HistoryMessage> history
) {
    public record HistoryMessage(String role, String content) {}
}
