package com.safeshield.dto;

import java.util.List;

public record ReportReadiness(
        boolean ready,
        String status,
        String reason,
        List<String> missingInfo,
        List<String> keyFacts,
        boolean schoolViolenceLikely
) {}
