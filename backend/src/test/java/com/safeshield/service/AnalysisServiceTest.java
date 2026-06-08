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
        assertTrue(result.expectedMeasureRange().get(1) >= 6, "반복 상해와 협박은 학급교체 수준까지 참고 범위에 포함해야 합니다.");
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
    void avoidsRepetitiveEvidenceItemsForGroupChatCyberCase() {
        ReportReadiness readiness = readySchoolViolence();

        var result = analysisService.analyze(
                "같은 반 친구들이 단톡방에서 계속 괴롭히고 욕설과 비방을 여러 번 했습니다. 캡처가 있고 불안해서 담임에게 신고하고 싶습니다.",
                readiness
        );

        long captureCount = result.evidenceGuide().stream()
                .filter(item -> item.contains("캡처"))
                .count();

        assertTrue(result.evidenceGuide().contains("대화방 전체 캡처"));
        assertTrue(result.evidenceGuide().contains("참여자·계정 정보 정리"));
        assertTrue(captureCount <= 1, "캡처류 증거가 여러 개 반복되지 않아야 합니다.");
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
                "저는 피해를 당한 입장입니다. 같은 반 친구가 단톡방에서 욕설과 비방을 여러 번 했습니다. " +
                        "캡처와 URL이 있고 불안해서 등교가 힘듭니다. 증거 정리와 신고 절차를 알고 싶습니다. " +
                        "확인 답변: 위 내용은 하나의 같은 사안이며 이 내용으로 리포트를 생성해도 됩니다.",
                4
        );
        var result = analysisService.analyze(
                "저는 피해를 당한 입장입니다. 같은 반 친구가 단톡방에서 욕설과 비방을 여러 번 했습니다. " +
                        "캡처와 URL이 있고 불안해서 등교가 힘듭니다. 증거 정리와 신고 절차를 알고 싶습니다. " +
                        "확인 답변: 위 내용은 하나의 같은 사안이며 이 내용으로 리포트를 생성해도 됩니다.",
                readiness
        );

        assertTrue(readiness.ready(), "행위, 관계, 시점, 증거, 영향, 목표, 최종 확인이 모두 확인되면 리포트 생성이 가능해야 합니다.");
        assertTrue(result.keyFindings().stream().anyMatch(item -> item.startsWith("관계 판단:")));
        assertTrue(result.keyFindings().stream().anyMatch(item -> item.startsWith("판단 신뢰도:")));
    }

    @Test
    void doesNotTreatVictimWordingAsPerpetrator() {
        ReportReadiness readiness = analysisService.assessReportReadiness(
                "저는 피해를 당한 입장입니다. 같은 반 친구에게 제가 욕을 먹었고 오늘 캡처가 있습니다. " +
                        "여러 번 반복됐고 불안해서 담임에게 상담하고 싶습니다. " +
                        "확인 답변: 위 내용은 하나의 같은 사안이며 이 내용으로 리포트를 생성해도 됩니다.",
                4
        );

        assertTrue(readiness.ready(), "피해자 문장도 사안 구조가 충분하면 리포트 준비가 되어야 합니다.");
        assertFalse(readiness.status().contains("가해"), "제가 욕을 먹었다는 표현을 가해자 관점으로 오판하면 안 됩니다.");
    }

    @Test
    void buildsPerpetratorFocusedReportGuidance() {
        ReportReadiness readiness = analysisService.assessReportReadiness(
                "저는 가해 또는 연루된 입장입니다. 같은 반 친구에게 제가 단톡방에서 여러 번 욕설을 했고 사진도 올렸습니다. " +
                        "캡처가 있고 오늘 담임에게 말하려고 합니다. 미안하고 사과와 피해 회복 방법을 알고 싶습니다. " +
                        "확인 답변: 위 내용은 하나의 같은 사안이며 이 내용으로 리포트를 생성해도 됩니다.",
                4
        );
        var result = analysisService.analyze(
                "저는 가해 또는 연루된 입장입니다. 같은 반 친구에게 제가 단톡방에서 여러 번 욕설을 했고 사진도 올렸습니다. " +
                        "캡처가 있고 오늘 담임에게 말하려고 합니다. 미안하고 사과와 피해 회복 방법을 알고 싶습니다. " +
                        "확인 답변: 위 내용은 하나의 같은 사안이며 이 내용으로 리포트를 생성해도 됩니다.",
                readiness
        );

        assertTrue(readiness.ready(), "가해자 관점도 핵심 사실이 확인되면 리포트 생성이 가능해야 합니다.");
        assertTrue(readiness.status().contains("가해"), "본인이 한 행동은 가해 또는 연루 가능성으로 분기해야 합니다.");
        assertTrue(result.evidenceGuide().contains("본인 행동 타임라인"));
        assertTrue(result.evidenceGuide().contains("사과·피해 회복 기록"));
        assertTrue(result.recommendedActions().stream().anyMatch(item -> item.contains("추가 연락")));
        assertTrue(result.keyFindings().stream().anyMatch(item -> item.contains("가해 또는 연루")));
    }

    @Test
    void asksFinalConfirmationWhenFirstMessageAlreadyHasAllFacts() {
        ReportReadiness first = analysisService.assessReportReadiness(
                "저는 피해를 당한 입장입니다. 같은 반 친구가 단톡방에서 욕설과 비방을 여러 번 했습니다. " +
                        "캡처와 URL이 있고 불안해서 증거 정리와 신고 절차를 알고 싶습니다.",
                1
        );
        ReportReadiness almost = analysisService.assessReportReadiness(
                "저는 피해를 당한 입장입니다. 같은 반 친구가 단톡방에서 욕설과 비방을 여러 번 했습니다. " +
                        "캡처와 URL이 있고 불안해서 증거 정리와 신고 절차를 알고 싶습니다.",
                4
        );
        ReportReadiness confirmed = analysisService.assessReportReadiness(
                "저는 피해를 당한 입장입니다. 같은 반 친구가 단톡방에서 욕설과 비방을 여러 번 했습니다. " +
                        "캡처와 URL이 있고 불안해서 증거 정리와 신고 절차를 알고 싶습니다. " +
                        "확인 답변: 위 내용은 하나의 같은 사안이며 이 내용으로 리포트를 생성해도 됩니다.",
                4
        );

        assertFalse(first.ready(), "첫 메시지에 정보가 충분해도 바로 리포트를 열지 않습니다.");
        assertTrue(first.missingInfo().contains("상담 내용을 조금 더 들은 뒤 리포트 생성"));
        assertFalse(almost.ready(), "핵심 정보가 충분해도 최종 확인 전에는 리포트를 열지 않습니다.");
        assertTrue(almost.missingInfo().contains("사안 내용 최종 확인"));
        assertTrue(confirmed.ready(), "최종 확인 답변 후에는 리포트 생성이 가능해야 합니다.");
    }

    @Test
    void doesNotOpenReportAfterOnlyTwoUserTurns() {
        ReportReadiness readiness = analysisService.assessReportReadiness(
                "저는 피해를 당한 입장입니다. 같은 반 친구가 단톡방에서 욕설과 비방을 여러 번 했습니다. " +
                        "캡처와 URL이 있고 불안해서 증거 정리와 신고 절차를 알고 싶습니다. " +
                        "확인 답변: 위 내용은 하나의 같은 사안이며 이 내용으로 리포트를 생성해도 됩니다.",
                2
        );

        assertFalse(readiness.ready(), "핵심 정보가 있어도 사용자 답변 2개만으로는 리포트를 열지 않습니다.");
        assertTrue(readiness.missingInfo().contains("상담 내용을 조금 더 들은 뒤 리포트 생성"));
    }

    @Test
    void doesNotAskUserToSelfLabelVictimOrPerpetrator() {
        ReportReadiness readiness = analysisService.assessReportReadiness(
                "같은 반 친구한테 맞아서 신고하려고 찾아봤습니다. 오늘 한 번 있었고 멍 사진이 있습니다. 너무 불안해서 어떻게 해야 할지 알고 싶습니다.",
                1
        );

        assertFalse(readiness.missingInfo().stream().anyMatch(item -> item.contains("입장")),
                "피해·가해 여부를 사용자가 직접 고르라고 묻지 않아야 합니다.");
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
