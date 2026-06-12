package com.safeshield.service;

import com.safeshield.dto.ReportReadiness;
import com.safeshield.model.Message;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatServiceTest {

    private static final String LAW_CONTEXT = """
            === 학교폭력 예방 및 대책에 관한 법률 ===
            제2조 (정의): 학교폭력의 정의
            제16조 (피해학생의 보호): 피해학생 보호 조치와 제16조의4에 따른 지원 연계

            === 형법 ===
            제307조 (명예훼손): 명예훼손 규정
            제311조 (모욕): 모욕 규정
            """;

    @Test
    void acceptsKoreanReplyWithAllowedCitation() {
        String reply = """
                SNS에 사진과 비방 글이 게시되어 많이 힘든 상황으로 보입니다.

                ⚖️ 관련 법률
                • 형법 제307조(명예훼손): 공개된 글의 내용과 범위에 따라 적용 여부를 확인할 수 있습니다.

                🗂️ 지금 보관해야 할 증거
                • 게시글 전체 화면과 URL
                • 작성 계정 정보

                💬 다음 단계
                보호자와 담임교사에게 알리고 117에 상담을 요청하세요.

                """;

        assertTrue(ChatService.isGeneratedReplyValid(reply, LAW_CONTEXT));
    }

    @Test
    void rejectsInventedArticleAndUnsupportedLaw() {
        String reply = """
                SNS 게시물로 힘든 상황입니다.

                ⚖️ 관련 법률
                • 학교폭력 예방 및 대책에 관한 법률 제16조의4: 피해자 지원
                • 정보통신망법 제70조: 온라인 명예훼손

                🗂️ 지금 보관해야 할 증거
                • 게시물 캡처

                💬 다음 단계
                117에 상담하세요.
                """;

        assertFalse(ChatService.isGeneratedReplyValid(reply, LAW_CONTEXT));
    }

    @Test
    void acceptsUpToThreeAllowedCitationsForCyberViolence() {
        String reply = """
                SNS 사진 게시와 비방 글이 함께 있는 사이버 폭력 관련 상담으로 보입니다.

                ⚖️ 관련 법률
                • 학교폭력 예방 및 대책에 관한 법률 제2조: 학교폭력 해당성을 확인할 수 있습니다.
                • 형법 제307조: 공개된 비방 내용에 따라 적용 가능성이 있습니다.
                • 형법 제311조: 모욕 표현 여부도 함께 확인할 수 있습니다.

                🗂️ 증거 확보
                • 게시글 전체 화면과 URL을 보관하세요.

                💬 다음 단계
                보호자와 담임에게 증거를 공유하고 117에 상담을 요청하세요.
                """;

        assertTrue(ChatService.isGeneratedReplyValid(reply, LAW_CONTEXT));
    }

    @Test
    void rejectsForeignLanguageWord() {
        String reply = """
                SNS 게시물로 힘든 상황입니다.

                ⚖️ 관련 법률
                • 형법 제307조: 적용 여부를 확인할 수 있습니다.

                🗂️ 지금 보관해야 할 증거
                • URL hoặc 게시글 번호

                💬 다음 단계
                117에 상담하세요.
                """;

        assertFalse(ChatService.isGeneratedReplyValid(reply, LAW_CONTEXT));
    }

    @Test
    void rejectsPromptTemplateLeak() {
        String reply = """
                이번에 새로 반영한 점: SNS 게시물이 아직 남아 있습니다.

                사건 유형·관계·증거 상태를 한 문장으로 요약: 같은 반 친구가 SNS에 사진을 올린 사건입니다.

                ⚖️ 관련 법률
                • 학교폭력 예방 및 대책에 관한 법률 제2조: 적용 가능성을 확인할 수 있습니다.

                🗂️ 증거 확보
                • 증거 항목 1: 캡처와 URL을 보관하세요.

                💬 다음 단계
                보호자와 담임에게 공유하세요.
                """;

        assertFalse(ChatService.isGeneratedReplyValid(reply, LAW_CONTEXT));
    }

    @Test
    void acceptsConversationalReplyWithoutAnalysisTemplate() {
        String reply = """
                지금 바로 분석으로 넘기기보다 먼저 상황을 정리해도 됩니다.
                방금 말한 불안감도 상담 기록에 남고, 리포트에서는 대응 필요성을 판단할 때 함께 반영됩니다.
                지금 가장 걱정되는 게 보복인지, 증거 부족인지, 학교에 말하는 것인지부터 하나만 골라도 됩니다.
                """;

        assertTrue(ChatService.isGeneratedConversationReplyValid(reply, LAW_CONTEXT));
    }

    @Test
    void rejectsAnalysisTemplateInConversationalReply() {
        String reply = """
                🔎 현재 판단
                감정 표현이 확인됩니다.

                ⚖️ 관련 법률
                • 학교폭력 예방 및 대책에 관한 법률 제2조: 적용 가능성이 있습니다.

                🗂️ 증거 확보
                • 캡처를 보관하세요.

                💬 다음 단계
                보호자에게 말하세요.
                """;

        assertFalse(ChatService.isGeneratedConversationReplyValid(reply, LAW_CONTEXT));
    }

    @Test
    void rejectsIrrelevantTeacherAdultQuestionForGroupChat() {
        String reply = """
                단체 채팅방에서 계속 괴롭힘을 겪고 있다면 혼자 견디기 어려운 상황입니다.
                방금 말한 내용은 채팅방 안에서 반복되는 괴롭힘으로 보입니다.
                혹시 그 채팅방에 참여하고 있는 친구들 중에 선생님이나 어른이 계신가요?
                """;

        assertFalse(ChatService.isGeneratedConversationReplyValid(reply, LAW_CONTEXT));
    }

    @Test
    void rejectsVictimBlamingReasonQuestion() {
        String reply = """
                같은 반 학생이라면 학교에서 함께 지내는 관계가 중요한 부분입니다.
                지금 겪고 있는 상황을 조금 더 자세히 들으려고 합니다.
                친구들이 단체 채팅방에서 계속 욕하고 놀리는데, 그 이유는 무엇이라고 생각하나요?
                """;

        assertFalse(ChatService.isGeneratedConversationReplyValid(reply, LAW_CONTEXT));
    }

    @Test
    void rejectsDanglingQuestionLeadInWithoutActualQuestion() {
        String reply = """
                게시물이나 사진이 친구들이 볼 수 있는 범위로 올라왔습니다.
                보호자나 담임선생님께 얘기해 보는 것도 좋은 방법입니다.
                이 상담에서 가장 필요한 도움이 무엇인지 다시 한번 물어보겠습니다.
                """;

        assertFalse(ChatService.isGeneratedConversationReplyValid(reply, LAW_CONTEXT));
    }

    @Test
    void rejectsAwkwardRepeatedConversationStyle() {
        String reply = """
                같은 반 학생이니깐요. 혼자 감당하지 않아도 된다는 걸 기억하세요.
                증거가 많이 있으니깐요. 캡처도 있으니깐요.
                """;

        assertFalse(ChatService.isGeneratedConversationReplyValid(reply, LAW_CONTEXT));
    }

    @Test
    void rejectsAwkwardSingleLineBecauseEnding() {
        String reply = "그런 일들이 며칠 동안 계속해서 올라와서요.";

        assertFalse(ChatService.isGeneratedConversationReplyValid(reply, LAW_CONTEXT));
    }

    @Test
    void rejectsUnsafePhysicalViolenceAdvice() {
        String reply = """
                학교에서 맞았고 멍이 들었는데요. 그 감정은 정말 힘들고 혼자 감당하기도 어려울 것 같아요.
                하지만 지금은 안전하고, 혼자서도 괜찮은 곳에 계신 것 같아요.
                멍이 들었을 때는 잘 쉬고, 물을 많이 마시고, 따뜻한 물로 목욕을 해보세요.
                """;

        assertFalse(ChatService.isGeneratedConversationReplyValid(reply, LAW_CONTEXT));
    }

    @Test
    void buildsContextSpecificFollowUpQuestionForGroupChat() {
        ReportReadiness readiness = needsMoreContext();

        List<String> questions = ChatService.previewConfirmationQuestions(
                readiness,
                "같은 반 친구들이 단톡방에서 계속 욕설과 비방을 하고 있습니다. 캡처가 있고 불안해서 신고하고 싶습니다.",
                4
        );

        assertTrue(questions.get(0).contains("단톡방에서는 괴롭힘이 어떤 방식"));
    }

    @Test
    void changesFollowUpQuestionAfterGroupChatPatternIsAnswered() {
        ReportReadiness readiness = needsMoreContext();

        List<String> questions = ChatService.previewConfirmationQuestions(
                readiness,
                "같은 반 친구들이 단톡방에서 계속 욕설과 비방을 하고 있습니다. " +
                        "확인 답변: 여러 명이 함께 조롱하거나 비방했습니다. 캡처가 있고 불안합니다.",
                5
        );

        assertTrue(questions.get(0).contains("알고 있는 어른이나 학교 담당자"));
    }

    @Test
    void buildsPhysicalViolenceFollowUpQuestion() {
        ReportReadiness readiness = needsMoreContext();

        List<String> questions = ChatService.previewConfirmationQuestions(
                readiness,
                "같은 반 친구가 오늘 밀쳐서 멍이 들었습니다. 멍 사진이 있고 보호자에게 말하려고 합니다.",
                4
        );

        assertTrue(questions.get(0).contains("어느 부위"));
    }

    @Test
    void buildsPerpetratorFollowUpQuestion() {
        ReportReadiness readiness = needsMoreContext();

        List<String> questions = ChatService.previewConfirmationQuestions(
                readiness,
                "제가 같은 반 친구에게 단톡방에서 욕설을 했고 사진도 올렸습니다. 미안해서 어떻게 해야 할지 알고 싶습니다.",
                4
        );

        assertTrue(questions.get(0).contains("완전히 중단"));
    }

    @Test
    void doesNotRepeatActorStopQuestionAfterRemainingPostAnswer() {
        ReportReadiness readiness = needsMoreContext();

        List<String> questions = ChatService.previewConfirmationQuestions(
                readiness,
                "제가 같은 반 친구에게 단톡방에서 욕설을 했고 사진도 올렸습니다. " +
                        "사과나 피해 회복 방법을 알고 싶습니다. " +
                        "확인 답변: 아직 남아 있는 게시물이나 대화가 있습니다.",
                5
        );

        assertFalse(questions.stream().anyMatch(question -> question.contains("완전히 중단")));
    }

    @Test
    void asksRoleConflictQuestionWhenVictimAndPerpetratorSignalsAreMixed() {
        ReportReadiness readiness = needsMoreContext();

        List<String> questions = ChatService.previewConfirmationQuestions(
                readiness,
                "같은 반 친구가 저한테 욕을 해서 신고하려고 했습니다. " +
                        "그런데 사실 저는 가해자입니다. 제가 단톡방에 사진을 올렸고 욕도 했습니다.",
                5
        );

        assertTrue(questions.get(0).contains("같은 사건 안에서 서로 충돌"));
    }

    @Test
    void doesNotUsePerpetratorQuestionForVictimWantingApology() {
        ReportReadiness readiness = new ReportReadiness(
                false,
                "추가 확인 필요",
                "원하는 도움을 확인해야 합니다.",
                List.of("원하는 도움이 무엇인지"),
                List.of("구체적인 사건 내용 확인"),
                true
        );

        List<String> questions = ChatService.previewConfirmationQuestions(
                readiness,
                "같은 반 친구가 저한테 욕을 했고 저는 사과 받고 싶습니다. 캡처가 있습니다.",
                4
        );

        assertTrue(questions.get(0).contains("가장 필요한 도움"));
        assertFalse(questions.get(0).contains("사과 방식"));
    }

    @Test
    void doesNotTreatConfirmationAnswerAsIrrelevantInput() {
        assertFalse(ChatService.shouldGuardIrrelevantInput("확인 답변: 몇 주 이상 지속됐습니다.", false));
        assertFalse(ChatService.shouldGuardIrrelevantInput("추가 설명: 내용 전체가 있습니다.", false));
        assertFalse(ChatService.shouldGuardIrrelevantInput("답변: 친구들이 볼 수 있는 범위로 올라왔습니다.", false));
        assertTrue(ChatService.shouldGuardIrrelevantInput("확인 답변: 똥싸기", false));
        assertTrue(ChatService.shouldGuardIrrelevantInput("추가 설명: 똥싸기", false));
        assertTrue(ChatService.shouldGuardIrrelevantInput("확인 답변: ㅁㄴㅇㄹ", false));
        assertTrue(ChatService.shouldGuardIrrelevantInput("똥싸기", false));
        assertTrue(ChatService.shouldGuardIrrelevantInput("응가", false));
        assertTrue(ChatService.shouldGuardIrrelevantInput("똥", false));
        assertTrue(ChatService.shouldGuardIrrelevantInput("밥 필요해", false));
        assertFalse(ChatService.shouldGuardIrrelevantInput("어떤 확인이 필요한가요?", false));
        assertFalse(ChatService.shouldGuardIrrelevantInput("뭘 더 확인해야 해요?", false));
        assertFalse(ChatService.shouldGuardIrrelevantInput("추가 질문이 뭐예요?", false));
        assertFalse(ChatService.shouldGuardIrrelevantInput("하고 싶은데 부끄러워요", false));
        assertFalse(ChatService.shouldGuardIrrelevantInput("말하고 싶은데 창피해요", false));
    }

    @Test
    void appendsNextConfirmationQuestionToReply() {
        String reply = ChatService.connectReplyToConfirmation(
                "같은 학교 학생들이 단체 채팅방에서 괴롭히는 상황이군요.",
                List.of(Map.of("question", "대화 전체가 보이는 캡처나 원본이 있나요?"))
        );

        assertTrue(reply.contains("확인을 위해 질문 하나 할게요."));
        assertTrue(reply.contains("대화 전체가 보이는 캡처나 원본이 있나요?"));
    }

    @Test
    void doesNotDuplicateConfirmationLeadInWhenAppendingQuestion() {
        String reply = ChatService.connectReplyToConfirmation(
                "내용 전체가 있다는 점은 중요합니다. 확인을 위해 질문 하나 할게요. 리포트에는 참여자와 시간이 중요합니다.",
                List.of(Map.of("question", "상대와의 관계는 어디에 가깝나요?"))
        );

        assertFalse(reply.contains("내용 전체가 있다는 점은 중요합니다. 확인을 위해 질문 하나 할게요."));
        assertTrue(reply.endsWith("확인을 위해 질문 하나 할게요. 상대와의 관계는 어디에 가깝나요?"));
    }

    @Test
    void doesNotRepeatQuestionAfterUserAnsweredItInConversationHistory() {
        ReportReadiness evidenceMissing = new ReportReadiness(
                false,
                "추가 확인 필요",
                "증거를 확인해야 합니다.",
                List.of("남아 있는 증거가 무엇인지"),
                List.of("구체적인 사건 내용 확인"),
                true
        );
        List<Message> history = List.of(
                transientMessage("user", "친구들이 단체 채팅방에서 계속 욕하고 놀립니다."),
                transientMessage("assistant", "대화 증거에는 무엇이 남아 있나요? 리포트에는 참여자, 시간, 앞뒤 맥락이 중요합니다."),
                transientMessage("user", "추가 설명: 내용 전체가 있습니다.")
        );

        List<String> questions = ChatService.previewConfirmationQuestions(evidenceMissing, history);

        assertFalse(questions.stream().anyMatch(question -> question.contains("대화 증거에는 무엇이 남아 있나요")));
    }

    @Test
    void treatsShortAnswerPrefixAsConfirmationAnswerForPostSpread() {
        ReportReadiness moreContext = needsMoreContext();
        List<Message> history = List.of(
                transientMessage("user", "같은 반 친구들이 SNS에 내 사진을 올렸다고 했고 URL과 캡처가 있습니다."),
                transientMessage("assistant", "확인을 위해 질문 하나 할게요. 게시물은 어느 범위로 보이나요?"),
                transientMessage("user", "답변: 친구들이 볼 수 있는 범위로 올라왔습니다.")
        );

        List<String> questions = ChatService.previewConfirmationQuestions(moreContext, history);

        assertFalse(questions.stream().anyMatch(question -> question.contains("공개 범위")));
    }

    @Test
    void doesNotAskFinalSameCaseQuestionAsNormalConfirmationPrompt() {
        ReportReadiness finalCheckMissing = new ReportReadiness(
                false,
                "추가 확인 필요",
                "최종 확인만 남았습니다.",
                List.of("사안 내용 최종 확인"),
                List.of("구체적인 사건 내용 확인", "상대방과 학교 관계 확인", "시점 또는 반복성 단서 확인"),
                true
        );

        List<String> questions = ChatService.previewConfirmationQuestions(
                finalCheckMissing,
                "같은 반 친구들이 단톡방에서 계속 욕설과 비방을 했고 캡처가 있습니다. 불안해서 신고 절차를 알고 싶습니다.",
                8
        );

        assertFalse(questions.stream().anyMatch(question -> question.contains("하나의 같은 사안")));
    }

    @Test
    void doesNotRepeatSameQuestionFamilyAfterUserResponded() {
        ReportReadiness timelineMissing = new ReportReadiness(
                false,
                "추가 확인 필요",
                "시점을 확인해야 합니다.",
                List.of("언제부터 몇 번 있었는지"),
                List.of("구체적인 사건 내용 확인"),
                true
        );
        List<Message> history = List.of(
                transientMessage("user", "같은 반 친구들이 단톡방에서 계속 욕설과 비방을 하고 있습니다. 캡처가 있습니다."),
                transientMessage("assistant", "확인을 위해 질문 하나 할게요. 언제부터 몇 번 있었고, 지금도 계속되고 있나요?"),
                transientMessage("user", "잘 모르겠어요.")
        );

        List<String> questions = ChatService.previewConfirmationQuestions(timelineMissing, history);

        assertFalse(questions.stream().anyMatch(question -> question.contains("언제부터")));
        assertFalse(questions.stream().anyMatch(question -> question.contains("빈도")));
    }

    @Test
    void treatsWholeConversationContentAsEvidenceAnswer() {
        ReportReadiness evidenceMissing = new ReportReadiness(
                false,
                "추가 확인 필요",
                "증거를 확인해야 합니다.",
                List.of("남아 있는 증거가 무엇인지"),
                List.of("구체적인 사건 내용 확인"),
                true
        );

        assertTrue(ChatService.previewConfirmationQuestions(
                evidenceMissing,
                "친구들이 단체 채팅방에서 계속 욕하고 놀립니다. 추가 설명: 내용 전체가 있습니다.",
                3
        ).isEmpty());
    }

    @Test
    void guardsExplicitNonsenseEvenInsideConversation() {
        assertTrue(ChatService.shouldGuardIrrelevantInput("배고파서 게임하고 싶다 ㅋㅋ", false));
        assertTrue(ChatService.shouldGuardIrrelevantInput("똥싸기", false));
        assertTrue(ChatService.shouldGuardIrrelevantInput("짜증나서 똥싸기", true));
        assertFalse(ChatService.shouldGuardIrrelevantInput("밥 필요해", true, false));
        assertFalse(ChatService.shouldGuardIrrelevantInput("무서워서 학교에 말하기가 걱정돼요", true));
        assertFalse(ChatService.shouldGuardIrrelevantInput("하고 싶은데 부끄러워요", true));
        assertFalse(ChatService.shouldGuardIrrelevantInput("친구가 게임 채팅에서 욕하고 놀려요", false));
        assertFalse(ChatService.shouldGuardIrrelevantInput("친구가 씨발이라고 욕했어요", false));
    }

    @Test
    void doesNotRepeatAnsweredCoreConfirmationQuestion() {
        ReportReadiness relationshipMissing = new ReportReadiness(
                false,
                "추가 확인 필요",
                "관계를 확인해야 합니다.",
                List.of("상대가 학교 관계자인지"),
                List.of("구체적인 사건 내용 확인"),
                true
        );
        ReportReadiness timelineMissing = new ReportReadiness(
                false,
                "추가 확인 필요",
                "시점을 확인해야 합니다.",
                List.of("언제부터 몇 번 있었는지"),
                List.of("구체적인 사건 내용 확인"),
                true
        );
        ReportReadiness evidenceMissing = new ReportReadiness(
                false,
                "추가 확인 필요",
                "증거를 확인해야 합니다.",
                List.of("남아 있는 증거가 무엇인지"),
                List.of("구체적인 사건 내용 확인"),
                true
        );

        assertTrue(ChatService.previewConfirmationQuestions(
                relationshipMissing,
                "확인 답변: 상대는 선후배 또는 학원 관계입니다.",
                3
        ).isEmpty());
        assertTrue(ChatService.previewConfirmationQuestions(
                timelineMissing,
                "확인 답변: 몇 주 이상 지속됐습니다.",
                3
        ).isEmpty());
        assertTrue(ChatService.previewConfirmationQuestions(
                evidenceMissing,
                "확인 답변: 참여자 목록과 보낸 시간이 보이는 캡처가 있습니다.",
                3
        ).isEmpty());
    }

    private ReportReadiness needsMoreContext() {
        return new ReportReadiness(
                false,
                "추가 확인 필요",
                "리포트 생성 전에 사안 구조를 더 확인해야 합니다.",
                List.of("상담 내용을 조금 더 들은 뒤 리포트 생성"),
                List.of("구체적인 사건 내용 확인"),
                true
        );
    }

    private static Message transientMessage(String role, String content) {
        Message message = new Message();
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
