package com.safeshield.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

public record MessageRequest(@JsonAlias("session_id") Long sessionId, String content) {}
