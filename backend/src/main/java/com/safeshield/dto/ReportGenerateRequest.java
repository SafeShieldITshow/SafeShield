package com.safeshield.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

public record ReportGenerateRequest(@JsonAlias("session_id") Long sessionId, String title) {}
