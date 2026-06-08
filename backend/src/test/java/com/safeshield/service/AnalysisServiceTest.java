package com.safeshield.service;

import com.safeshield.dto.ReportReadiness;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void buildsSpecificEvidenceGuideForCyberPosting() {
        ReportReadiness readiness = readySchoolViolence();

        var result = analysisService.analyze(
                "같은 반 친구가 SNS에 제 사진과 비방 글을 올렸고 캡처와 URL이 있습니다.",
                readiness
        );

        assertTrue(result.evidenceGuide().contains("게시물 전체 화면 캡처"));
        assertTrue(result.evidenceGuide().contains("URL·작성자·게시시간 기록"));
        assertFalse(result.evidenceGuide().contains("진단서·진료 기록"));
    }

    @Test
    void differentiatesRiskWithinCyberViolenceCases() {
        ReportReadiness readiness = readySchoolViolence();

        var oneOff = analysisService.analyze(
                "같은 반 친구가 SNS에 제 사진과 비방 글을 한 번 올렸고 캡처와 URL이 있습니다.",
                readiness
        );
        var repeated = analysisService.analyze(
                "같은 반 친구들이 단톡방과 SNS에 제 사진과 비방 글을 계속 올리고 여러 명이 댓글로 조롱합니다. 아직도 반복되고 등교가 불안합니다.",
                readiness
        );

        assertTrue(repeated.riskScore() > oneOff.riskScore() + 1.0,
                "반복, 공개 확산, 불안이 있으면 1회성 게시보다 위험도가 뚜렷하게 높아야 합니다.");
        assertTrue(oneOff.riskScore() < 5.5, "1회성 사이버 비방은 고위험으로 과측정하지 않아야 합니다.");
    }

    @Test
    void differentiatesMinorAndSeverePhysicalCases() {
        ReportReadiness readiness = readySchoolViolence();

        var minor = analysisService.analyze(
                "같은 반 친구가 어제 한 번 밀쳐서 멍이 들었고 사진이 있습니다.",
                readiness
        );
        var severe = analysisService.analyze(
                "같은 반 친구들이 몇 달 동안 여러 번 때렸고 출혈과 골절로 병원 치료를 받았습니다. 계속 찾아와 때리겠다고 협박합니다.",
                readiness
        );

        assertTrue(severe.riskScore() > minor.riskScore() + 2.0,
                "반복 상해와 협박은 단발성 멍보다 훨씬 높게 산정해야 합니다.");
        assertTrue(minor.riskScore() < 6.5, "단발성 경미 상해는 자동으로 최고위험이 되지 않아야 합니다.");
    }

    @Test
    void rejectsIrrelevantInputAsNotReadyForReport() {
        ReportReadiness readiness = analysisService.assessReportReadiness("똥싸기", 2);
        var result = analysisService.analyze("똥싸기", readiness);

        assertFalse(readiness.ready(), "학교폭력과 무관한 입력은 리포트 준비 완료가 되면 안 됩니다.");
        assertFalse(readiness.schoolViolenceLikely(), "학교폭력 단서가 없는 입력은 학교폭력 가능성으로 보지 않아야 합니다.");
        assertTrue(result.violenceTypes().isEmpty(), "무관 입력을 언어 폭력으로 강제 분류하면 안 됩니다.");
        assertTrue(result.riskScore() <= 4.5, "무관 입력은 위험도를 낮게 제한해야 합니다.");
    }

    @Test
    void requiresDefiniteSchoolRelationshipBeforeReportReady() {
        ReportReadiness readiness = analysisService.assessReportReadiness(
                "친구가 계속 욕을 했고 캡처가 있습니다. 여러 번 반복됐습니다.",
                2
        );

        assertFalse(readiness.ready(), "친구라는 표현만으로 학교 관계를 확정하고 리포트를 열면 안 됩니다.");
        assertTrue(readiness.missingInfo().contains("상대가 학교 관계자인지"));
    }

    @Test
    void becomesReadyWhenCaseStructureIsConfirmed() {
        ReportReadiness readiness = analysisService.assessReportReadiness(
                "같은 반 친구가 단톡방에서 욕설과 비방을 여러 번 했습니다. 캡처와 URL이 있습니다.",
                2
        );
        var result = analysisService.analyze(
                "같은 반 친구가 단톡방에서 욕설과 비방을 여러 번 했습니다. 캡처와 URL이 있습니다.",
                readiness
        );

        assertTrue(readiness.ready(), "행위, 관계, 시점, 증거가 모두 확인되면 리포트 생성이 가능해야 합니다.");
        assertTrue(result.keyFindings().stream().anyMatch(item -> item.startsWith("관계 판단:")));
        assertTrue(result.keyFindings().stream().anyMatch(item -> item.startsWith("판단 신뢰도:")));
    }

    @Test
    void asksFinalConfirmationWhenFirstMessageAlreadyHasAllFacts() {
        ReportReadiness first = analysisService.assessReportReadiness(
                "같은 반 친구가 단톡방에서 욕설과 비방을 여러 번 했습니다. 캡처와 URL이 있습니다.",
                1
        );
        ReportReadiness confirmed = analysisService.assessReportReadiness(
                "같은 반 친구가 단톡방에서 욕설과 비방을 여러 번 했습니다. 캡처와 URL이 있습니다. " +
                        "확인 답변: 위 내용은 하나의 같은 사안이며 이 내용으로 리포트를 생성해도 됩니다.",
                2
        );

        assertFalse(first.ready(), "첫 메시지에 정보가 충분해도 최종 확인 없이 바로 리포트를 열지 않습니다.");
        assertTrue(first.missingInfo().contains("사안 내용 최종 확인"));
        assertTrue(confirmed.ready(), "최종 확인 답변 후에는 리포트 생성이 가능해야 합니다.");
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
