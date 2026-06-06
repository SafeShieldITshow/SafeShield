package com.safeshield.dto;

import java.util.List;

public record AnalysisResult(
        List<String> violenceTypes,
        double riskScore,
        List<String> matchedLaws,
        List<Double> lawRelevanceScores,
        List<Integer> expectedMeasureRange,
        List<String> evidenceGuide,
        String assessmentStatus,
        List<String> keyFindings,
        List<String> recommendedActions
) {}
