package com.safeshield.service;

import org.junit.jupiter.api.Test;

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
}
