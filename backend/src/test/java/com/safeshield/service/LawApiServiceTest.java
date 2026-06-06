package com.safeshield.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LawApiServiceTest {

    @Test
    void articleMatchingUsesExactArticleNumber() {
        assertTrue(LawApiService.articleMatches("2", "제2조"));
        assertTrue(LawApiService.articleMatches("44의7", "44조의7"));
        assertFalse(LawApiService.articleMatches("24", "2"));
        assertFalse(LawApiService.articleMatches("257", "57"));
    }

    @Test
    void lawNameNormalizationRemovesMarkupAndSpacing() {
        assertEquals(
                "학교폭력예방및대책에관한법률",
                LawApiService.normalizeLawName("<b>학교폭력 예방 및 대책에 관한 법률</b>")
        );
    }

    @Test
    void returnsFallbackContextWhenExternalLawApiIsUnavailable() {
        LawApiService service = new LawApiService();

        String context = service.getContextForViolenceTypes(List.of("사이버 폭력"));

        assertTrue(context.contains("학교폭력 예방 및 대책에 관한 법률"));
        assertTrue(context.contains("형법"));
        assertTrue(context.contains("제307조"));
    }

    @Test
    void narrowsLawContextToCaseSpecificArticles() {
        LawApiService service = new LawApiService();

        String context = service.getContextForCase(
                "같은 반 친구가 SNS에 제 사진과 비방 글을 올렸고 URL도 있습니다.",
                List.of("사이버 폭력")
        );

        assertTrue(context.contains("학교폭력 예방 및 대책에 관한 법률"));
        assertTrue(context.contains("제307조"));
        assertFalse(context.contains("제257조"));
        assertFalse(context.contains("제350조"));
    }
}
