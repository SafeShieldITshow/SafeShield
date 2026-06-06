package com.safeshield.service;

import com.safeshield.dto.ReportReadiness;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisServiceTest {

    private final AnalysisService analysisService = new AnalysisService(
            new LawDataService(),
            new EvidenceGuideService()
    );

    @Test
    void keepsSimpleCyberInsultBelowHighRisk() {
        ReportReadiness readiness = readySchoolViolence();

        var result = analysisService.analyze(
                "같은 반 친구가 SNS에 제 사진과 비방 댓글을 한 번 올렸고 캡처가 있습니다.",
                readiness
        );

        assertTrue(result.riskScore() < 6.0, "일반 사이버 비방은 고위험으로 과측정하지 않아야 합니다.");
        assertTrue(result.expectedMeasureRange().get(1) <= 5, "일반 사이버 비방의 조치 범위 상한은 중간 이하가 적절합니다.");
    }

    @Test
    void raisesRiskForRepeatedPhysicalInjuryAndThreat() {
        ReportReadiness readiness = readySchoolViolence();

        var result = analysisService.analyze(
                "같은 반 친구가 여러 번 때렸고 멍과 출혈이 생겼습니다. 계속 찾아가서 때리겠다고 협박했고 병원 진단서가 있습니다.",
                readiness
        );

        assertTrue(result.riskScore() >= 8.0, "반복 신체 폭력과 상해, 협박은 높은 위험도로 봐야 합니다.");
        assertTrue(result.expectedMeasureRange().get(1) >= 7, "고위험 사안은 조치 범위 상한이 높아야 합니다.");
    }

    private ReportReadiness readySchoolViolence() {
        return new ReportReadiness(
                true,
                "학교폭력 가능성 검토",
                "학교 관계와 폭력 유형 단서가 확인되어 리포트 생성이 가능합니다.",
                List.of(),
                List.of("구체적인 사건 내용 확인", "상대방 또는 학교 관계 단서 확인"),
                true
        );
    }
}
