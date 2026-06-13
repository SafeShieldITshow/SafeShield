package com.safeshield.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safeshield.dto.ReportReadiness;
import com.safeshield.model.Message;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportServiceTest {

    private final ReportService reportService = new ReportService(
            null,
            null,
            null,
            new AnalysisService(new LawDataService(), new EvidenceGuideService()),
            new ObjectMapper()
    );

    @Test
    void temporaryReportTitlePrefersCyberTypeForSnsPhotoCase() {
        Map<String, Object> report = reportService.generateTemporary(List.of(
                user("SNS에 제 사진과 비방 글이 올라왔습니다."),
                user("확인 답변: 상대가 학교 관계자인지는 아직 모르겠습니다."),
                user("확인 답변: 며칠 동안 반복됐습니다."),
                user("확인 답변: 불안하거나 두려운 영향이 있습니다."),
                user("확인 답변: 보복이나 반복을 막고 안전하게 보호받고 싶습니다."),
                user("확인 답변: 댓글이나 추가 조롱이 붙었습니다. 공개 게시물로 올라왔고 다른 사람에게 공유되거나 퍼졌습니다."),
                user("확인 답변: 게시물 URL이나 링크가 있습니다.")
        ), null);

        assertEquals("학교폭력 해당성 낮음 - 사이버 폭력", report.get("title"));
        assertTrue(((List<?>) report.get("violence_types")).contains("언어 폭력"));
        assertTrue(((List<?>) report.get("violence_types")).contains("사이버 폭력"));
    }

    @Test
    void temporaryReportUsesSuppliedReadinessFromChatFlow() {
        ReportReadiness readiness = new ReportReadiness(
                true,
                "학교폭력 가능성 검토",
                "사용자의 확인 답변 이력을 바탕으로 제한사항을 포함해 리포트를 생성할 수 있습니다.",
                List.of(),
                List.of("구체적인 사건 내용 확인", "질문 답변 이력으로 증거 또는 발생 경로 확인"),
                true
        );

        Map<String, Object> report = reportService.generateTemporary(List.of(
                user("같은 반 친구가 SNS에 제 사진을 올리고 비방했습니다. 며칠 반복됐고 신고 절차를 알고 싶습니다."),
                user("정확히는 아직 정리 못 했어요.")
        ), null, readiness);

        assertEquals("학교폭력 가능성 검토", report.get("assessment_status"));
        assertTrue(((List<?>) report.get("violence_types")).contains("사이버 폭력"));
    }

    private static Message user(String content) {
        Message message = new Message();
        message.setRole("user");
        message.setContent(content);
        return message;
    }
}
