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
    void usesChatSpecificEvidenceWithoutUnrelatedRecording() {
        ReportReadiness readiness = readySchoolViolence();

        var result = analysisService.analyze(
                "같은 반 친구들이 단톡방에서 계속 욕설과 비방을 하고 있습니다. 참여자와 시간이 보이는 캡처가 있습니다.",
                readiness
        );

        assertTrue(result.evidenceGuide().contains("대화방 전체 캡처"));
        assertTrue(result.evidenceGuide().contains("참여자·계정 정보 정리"));
        assertTrue(result.evidenceGuide().contains("대화 내보내기 원본"));
        assertFalse(result.evidenceGuide().contains("녹음 파일 원본"),
                "문자·단톡 사안에 녹음 파일을 기본 증거처럼 보여주면 안 됩니다.");
        assertFalse(result.evidenceGuide().contains("URL·작성자·게시시간 기록"),
                "단톡 사안에 게시물 URL 증거를 섞으면 안 됩니다.");
    }

    @Test
    void usesPostSpecificEvidenceForPhotoSpreadCase() {
        ReportReadiness readiness = readySchoolViolence();

        var result = analysisService.analyze(
                "같은 반 친구가 SNS에 제 사진을 올렸고 댓글로 조롱이 붙었습니다. 게시물 URL과 캡처가 있습니다.",
                readiness
        );

        assertTrue(result.evidenceGuide().contains("게시물 전체 화면 캡처"));
        assertTrue(result.evidenceGuide().contains("URL·작성자·게시시간 기록"));
        assertTrue(result.evidenceGuide().contains("댓글·공유 범위 기록"));
        assertTrue(result.evidenceGuide().contains("원본 파일 백업"));
        assertFalse(result.evidenceGuide().contains("대화 내보내기 원본"),
                "게시물 사안에 단톡방 내보내기 증거를 섞으면 안 됩니다.");
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
    void keepsOneOffMinorVerbalCaseLowWhenNoImpactAndResolved() {
        ReportReadiness readiness = readySchoolViolence();

        var result = analysisService.analyze(
                "같은 반 친구가 오늘 한 번 욕을 했지만 바로 사과했고 현재 불편한 점은 없습니다.",
                readiness
        );

        assertTrue(result.riskScore() <= 3.0,
                "1회성 경미 발언, 사과, 영향 없음이 함께 있으면 위험도를 낮게 제한해야 합니다.");
    }

    @Test
    void doesNotOverrateMildComplimentOrMisunderstandingAsViolence() {
        ReportReadiness readiness = analysisService.assessReportReadiness(
                "같은 반 친구에게 귀엽다고 했는데 상대가 그냥 기분이 상한 정도라고 했습니다. 욕설로 볼 일은 아니고 오늘 한 번 있었으며 불편한 점은 없다고 합니다. 어떻게 정리해야 하나요? " +
                        "확인 답변: 위 내용은 하나의 같은 사안이며 이 내용으로 리포트를 생성해도 됩니다.",
                6
        );
        var result = analysisService.analyze(
                "같은 반 친구에게 귀엽다고 했는데 상대가 그냥 기분이 상한 정도라고 했습니다. 욕설로 볼 일은 아니고 오늘 한 번 있었으며 불편한 점은 없다고 합니다. 어떻게 정리해야 하나요? " +
                        "확인 답변: 위 내용은 하나의 같은 사안이며 이 내용으로 리포트를 생성해도 됩니다.",
                readiness
        );

        assertFalse(result.violenceTypes().contains("언어 폭력"), "가벼운 칭찬이나 오해 표현을 욕설·언어폭력으로 바로 분류하면 안 됩니다.");
        assertTrue(result.riskScore() <= 3.2, "가벼운 표현·1회성·영향 없음은 위험도를 낮게 산정해야 합니다.");
    }

    @Test
    void buildsDifferentGuidanceForDifferentCaseContexts() {
        ReportReadiness readiness = readySchoolViolence();

        var groupChat = analysisService.analyze(
                "같은 반 친구들이 단톡방에서 계속 욕설과 비방을 하고 있습니다. 캡처가 있고 불안해서 담임에게 신고하고 싶습니다.",
                readiness
        );
        var physical = analysisService.analyze(
                "같은 반 친구가 오늘 한 번 밀쳐서 멍이 들었습니다. 멍 사진이 있고 보호자에게 말하려고 합니다.",
                readiness
        );

        assertTrue(groupChat.keyFindings().stream().anyMatch(item -> item.contains("단체 채팅방")));
        assertTrue(groupChat.recommendedActions().stream().anyMatch(item -> item.contains("참여자 목록")));
        assertTrue(physical.keyFindings().stream().anyMatch(item -> item.contains("때리거나 밀치는 신체 폭력")));
        assertTrue(physical.recommendedActions().stream().anyMatch(item -> item.contains("가까운 사진과 전체 위치 사진")));
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
    void doesNotRatePhysicalKeywordAloneAsHighRisk() {
        ReportReadiness readiness = readySchoolViolence();

        var oneOff = analysisService.analyze(
                "학원 선배가 오늘 한 번 밀쳤고 병원은 안 갔으며 협박은 없습니다. 어떻게 해야 할지 알고 싶습니다.",
                readiness
        );
        var repeatedWithoutInjury = analysisService.analyze(
                "학원 선배가 몇 주 동안 여러 번 밀쳤지만 다친 곳은 없습니다. 목격자가 있고 상담을 원합니다.",
                readiness
        );

        assertTrue(oneOff.riskScore() <= 5.3,
                "관계 표현이나 폭행 키워드만으로 고위험 점수가 나오면 안 됩니다.");
        assertTrue(repeatedWithoutInjury.riskScore() < 7.0,
                "반복 신체 접촉이라도 상해·협박·집단성 단서가 없으면 8점대 고위험으로 올리지 않아야 합니다.");
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
                8
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
    void recognizesDurationConfirmationAnswerAsTimelineFact() {
        ReportReadiness readiness = analysisService.assessReportReadiness(
                "같은 학원 선배가 카톡에서 욕설과 비방을 했습니다. 캡처가 있고 불안해서 신고 상담을 원합니다. " +
                        "확인 답변: 상대는 선후배 또는 학원 관계입니다. " +
                        "확인 답변: 몇 주 이상 지속됐습니다.",
                7
        );

        assertFalse(readiness.missingInfo().contains("언제부터 몇 번 있었는지"),
                "확인 카드의 지속 기간 답변은 시간·반복성 정보로 반영되어야 합니다.");
    }

    @Test
    void doesNotTreatVictimWordingAsPerpetrator() {
        ReportReadiness readiness = analysisService.assessReportReadiness(
                "저는 피해를 당한 입장입니다. 같은 반 친구에게 제가 욕을 먹었고 오늘 캡처가 있습니다. " +
                        "여러 번 반복됐고 불안해서 담임에게 상담하고 싶습니다. " +
                        "확인 답변: 위 내용은 하나의 같은 사안이며 이 내용으로 리포트를 생성해도 됩니다.",
                7
        );

        assertTrue(readiness.ready(), "피해자 문장도 사안 구조가 충분하면 리포트 준비가 되어야 합니다.");
        assertFalse(readiness.status().contains("가해"), "제가 욕을 먹었다는 표현을 가해자 관점으로 오판하면 안 됩니다.");
    }

    @Test
    void sexualContactCaseBecomesReadyAfterCoreFactsWithoutExtraSurveyLoop() {
        String text = "남자인데 이런 것도 상담해도 되나요? 어떻게 해야 할지 모르겠습니다. " +
                "확인 답변: 원하지 않는 신체 접촉이 있었습니다. 말하기가 부끄럽지만 원하지 않는데 자꾸 만졌습니다. " +
                "확인 답변: 상대는 같은 반 학생입니다. " +
                "확인 답변: 최근 비슷한 일이 여러 번 반복됐습니다. 추가 설명: 한 달 정도 된 것 같고 또 그럴까 봐 학교 가기가 무섭습니다. " +
                "확인 답변: 아직 확보한 증거는 없습니다. 추가 설명: 신체 접촉이라 캡처는 없고 그때 주변에 애들이 있었습니다. " +
                "확인 답변: 학교나 담임에게 말하는 것이 걱정됩니다. 확인 답변: 보복이나 반복이 가장 걱정됩니다.";

        ReportReadiness readiness = analysisService.assessReportReadiness(text, 5);
        var result = analysisService.analyze(text, readiness);

        assertTrue(readiness.ready(), "성폭력 핵심 사실이 확인되면 추가 설문 루프 없이 리포트 생성이 가능해야 합니다.");
        assertTrue(result.violenceTypes().contains("성폭력"));
        assertFalse(result.violenceTypes().contains("신체 폭력"), "원하지 않은 성적 접촉을 일반 신체폭력 선택지로 오분류하면 안 됩니다.");
    }

    @Test
    void treatsOwnPhotoPostedOnSnsAsVictimContextUnlessUserClearlyAdmitsPosting() {
        String text = "저는 피해를 당한 입장입니다. 같은 반 친구들이 오늘 SNS에 내 사진을 올렸다고 했고 " +
                "친구들이 볼 수 있는 범위로 올라왔습니다. URL과 캡처가 있고 불안해서 증거 정리와 신고 절차를 알고 싶습니다. " +
                "확인 답변: 위 내용은 하나의 같은 사안이며 이 내용으로 리포트를 생성해도 됩니다.";

        ReportReadiness readiness = analysisService.assessReportReadiness(text, 8);
        var result = analysisService.analyze(text, readiness);

        assertTrue(readiness.ready(), "내 사진이 SNS에 올라온 피해 문장도 충분한 사실이 있으면 리포트 준비가 되어야 합니다.");
        assertFalse(readiness.status().contains("가해"), "내 사진이 올라왔다는 표현을 사용자가 직접 게시한 것으로 오판하면 안 됩니다.");
        assertFalse(result.evidenceGuide().contains("사과·피해 회복 기록"));
        assertTrue(result.evidenceGuide().contains("URL·작성자·게시시간 기록"));
    }

    @Test
    void treatsUnknownSuspectAsConfirmedLimitationInsteadOfMissingRelationship() {
        String text = "SNS에 제 사진과 비방 글이 올라왔고 친구들이 볼 수 있는 범위로 퍼졌습니다. " +
                "며칠 동안 반복됐고 캡처와 URL은 있지만 용의자 특정이 어렵고 누가 올렸는지 모르겠습니다. " +
                "불안해서 증거 정리와 신고 절차를 알고 싶습니다.";

        ReportReadiness readiness = analysisService.assessReportReadiness(text, 8);
        var result = analysisService.analyze(text, readiness);

        assertTrue(readiness.ready(), "특정 불가는 답변된 제한사항으로 처리하고 같은 관계 질문을 반복하지 않아야 합니다. missing=" + readiness.missingInfo());
        assertFalse(readiness.missingInfo().contains("상대가 학교 관계자인지"));
        assertTrue(result.keyFindings().stream().anyMatch(item -> item.contains("학교 관계") && item.contains("불명확")));
    }

    @Test
    void snsPhotoReportOpensWhenSchoolRelationshipIsUnknownButCoreFactsAreComplete() {
        String text = "SNS에 제 사진과 비방 글이 올라왔습니다. " +
                "확인 답변: 상대가 학교 관계자인지는 아직 모르겠습니다. " +
                "확인 답변: 며칠 동안 반복됐습니다. " +
                "확인 답변: 불안하거나 두려운 영향이 있습니다. " +
                "확인 답변: 보복이나 반복을 막고 안전하게 보호받고 싶습니다. " +
                "확인 답변: 상대와 어떻게 거리를 둬야 할지 알고 싶습니다. " +
                "확인 답변: 댓글이나 추가 조롱이 붙었습니다. " +
                "확인 답변: 공개 게시물로 올라왔습니다. " +
                "확인 답변: 다른 사람에게 공유되거나 퍼졌습니다. " +
                "확인 답변: 게시물 URL이나 링크가 있습니다.";

        ReportReadiness readiness = analysisService.assessReportReadiness(text, 7);
        var result = analysisService.analyze(text, readiness);

        assertTrue(readiness.ready(), "학교 관계가 아직 불명확해도 핵심 사실이 모두 확인되면 제한사항이 있는 리포트를 생성해야 합니다.");
        assertFalse(readiness.missingInfo().contains("상대가 학교 관계자인지"));
        assertTrue(readiness.status().contains("학교폭력 해당성 낮음"));
        assertTrue(result.keyFindings().stream().anyMatch(item -> item.contains("학교 관계") && item.contains("불명확")));
    }

    @Test
    void unclearFriendRelationshipIsAConfirmedLimitation() {
        String text = "SNS에 제 사진과 비방 글이 올라왔습니다. 며칠 동안 반복됐고 URL과 캡처가 있습니다. " +
                "불안해서 증거 정리와 신고 절차를 알고 싶습니다. 상대는 안 좋게 지내던 친구입니다.";

        ReportReadiness readiness = analysisService.assessReportReadiness(text, 7);

        assertTrue(readiness.ready(), "관계 설명을 했으면 학교 관계가 확정되지 않아도 같은 질문을 반복하지 않고 리포트를 생성해야 합니다.");
        assertFalse(readiness.missingInfo().contains("상대가 학교 관계자인지"));
    }

    @Test
    void directPerpetratorAdmissionOverridesEarlierVictimContext() {
        ReportReadiness readiness = analysisService.assessReportReadiness(
                "처음에는 같은 반 친구가 저한테 욕을 해서 신고하려고 한다고 말했습니다. " +
                        "그런데 사실 저는 가해자입니다. 제가 오늘 한 번 단톡방에 사진을 올렸고 욕도 했습니다. " +
                        "캡처가 있고 사과나 피해 회복 방법을 알고 싶습니다. " +
                        "확인 답변: 같은 사안에서 서로 충돌한 내용입니다. " +
                        "확인 답변: 위 내용은 하나의 같은 사안이며 이 내용으로 리포트를 생성해도 됩니다.",
                8
        );

        assertTrue(readiness.status().contains("가해"),
                "이전 피해 맥락이 있어도 직접적인 가해 인정은 가해 또는 연루 관점으로 처리해야 합니다.");
    }

    @Test
    void treatsGuardianMentionAsVictimWhenUserIsSeekingHelpForSelf() {
        ReportReadiness readiness = analysisService.assessReportReadiness(
                "같은 반 친구가 저를 때려서 멍 사진이 있고 오늘 보호자에게 말하려고 합니다. " +
                        "불안해서 신고 절차를 알고 싶고, 확인 답변: 위 내용은 하나의 같은 사안이며 이 내용으로 리포트를 생성해도 됩니다.",
                7
        );

        assertTrue(readiness.ready(), "보호자에게 말하겠다는 피해자 문장을 보호자 관점으로 오판하면 안 됩니다.");
        assertFalse(readiness.status().contains("목격자"), "피해자가 보호자를 언급한 것만으로 목격자 또는 보호자 관점이 되면 안 됩니다.");
        assertFalse(readiness.status().contains("가해"), "피해자 문장이 가해 관점으로 바뀌면 안 됩니다.");
    }

    @Test
    void buildsPerpetratorFocusedReportGuidance() {
        ReportReadiness readiness = analysisService.assessReportReadiness(
                "저는 가해 또는 연루된 입장입니다. 같은 반 친구에게 제가 단톡방에서 여러 번 욕설을 했고 사진도 올렸습니다. " +
                        "캡처가 있고 오늘 담임에게 말하려고 합니다. 미안하고 사과와 피해 회복 방법을 알고 싶습니다. " +
                        "확인 답변: 위 내용은 하나의 같은 사안이며 이 내용으로 리포트를 생성해도 됩니다.",
                8
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
    void opensReportWhenCoreFactsAndConversationDepthAreEnough() {
        ReportReadiness first = analysisService.assessReportReadiness(
                "저는 피해를 당한 입장입니다. 같은 반 친구가 단톡방에서 욕설과 비방을 여러 번 했습니다. " +
                        "캡처와 URL이 있고 불안해서 증거 정리와 신고 절차를 알고 싶습니다.",
                1
        );
        ReportReadiness enough = analysisService.assessReportReadiness(
                "저는 피해를 당한 입장입니다. 같은 반 친구가 단톡방에서 욕설과 비방을 여러 번 했습니다. " +
                        "캡처와 URL이 있고 불안해서 증거 정리와 신고 절차를 알고 싶습니다.",
                8
        );
        ReportReadiness confirmed = analysisService.assessReportReadiness(
                "저는 피해를 당한 입장입니다. 같은 반 친구가 단톡방에서 욕설과 비방을 여러 번 했습니다. " +
                        "캡처와 URL이 있고 불안해서 증거 정리와 신고 절차를 알고 싶습니다. " +
                        "확인 답변: 위 내용은 하나의 같은 사안이며 이 내용으로 리포트를 생성해도 됩니다.",
                8
        );

        assertFalse(first.ready(), "첫 메시지에 정보가 충분해도 바로 리포트를 열지 않습니다.");
        assertTrue(first.missingInfo().contains("상담 내용을 조금 더 들은 뒤 리포트 생성"));
        assertTrue(enough.ready(), "핵심 정보와 최소 대화량이 충분하면 별도 최종 확인 없이 리포트 생성이 가능해야 합니다.");
        assertFalse(enough.missingInfo().contains("사안 내용 최종 확인"));
        assertTrue(confirmed.ready(), "최종 확인 답변이 있어도 리포트 생성이 가능해야 합니다.");
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
    void acceptsNoImpactAnswerAsImpactConfirmation() {
        ReportReadiness readiness = analysisService.assessReportReadiness(
                "저는 피해를 당한 입장입니다. 같은 반 친구가 단톡방에서 욕설을 오늘 한 번 했습니다. " +
                        "캡처가 있고 불편한 점은 없습니다. 증거 정리 방법을 알고 싶습니다. " +
                        "확인 답변: 위 내용은 하나의 같은 사안이며 이 내용으로 리포트를 생성해도 됩니다.",
                7
        );

        assertFalse(readiness.missingInfo().contains("피해 영향이나 피해 회복을 위해 이미 한 일이 있는지"),
                "불편한 점 없음도 영향 확인 답변으로 인정해야 합니다.");
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
