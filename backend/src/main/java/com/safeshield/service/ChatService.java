package com.safeshield.service;

import com.safeshield.model.Message;
import com.safeshield.model.Session;
import com.safeshield.model.User;
import com.safeshield.dto.AnalysisResult;
import com.safeshield.dto.MessageRequest.HistoryMessage;
import com.safeshield.dto.ReportReadiness;
import com.safeshield.repository.MessageRepository;
import com.safeshield.repository.ReportRepository;
import com.safeshield.repository.SessionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private enum ReplyMode {
        ANALYSIS,
        CONVERSATION
    }

    private enum CaseDomain {
        PERPETRATOR,
        SEXUAL,
        PHYSICAL,
        CYBER_CHAT,
        CYBER_POST,
        SOCIAL_EXCLUSION,
        EXTORTION,
        STALKING,
        GENERAL
    }

    private static final int AI_HISTORY_LIMIT = 18;
    private static final int GUEST_HISTORY_LIMIT = 24;
    private static final int MEMORY_RECENT_USER_LIMIT = 8;
    private static final String CHEAPEST_DEEPSEEK_MODEL = "deepseek-v4-flash";
    private static final int DEEPSEEK_DEFAULT_MAX_TOKENS = 900;
    private static final int DEEPSEEK_MIN_MAX_TOKENS = 500;
    private static final int DEEPSEEK_HARD_MAX_TOKENS = 1400;
    private static final String GROUP_CHAT_EVIDENCE_FIRST_ADVICE =
            "단체 채팅방에서는 먼저 참여자 목록·보낸 시간·앞뒤 대화 맥락이 보이게 캡처하고, 가능하면 대화 내보내기 원본을 따로 보관하는 것이 중요합니다.";
    private static final Pattern UNSAFE_GROUP_CHAT_EXIT_SENTENCE_PATTERN = Pattern.compile(
            "[^\\n.!?。！？]*?(?:단체\\s*채팅방|단톡방|채팅방|대화방|단톡)[^\\n.!?。！？]*?(?:나가|퇴장|빠져나오)[^\\n.!?。！？]*(?:[.!?。！？]|$)"
    );
    private static final Pattern NEXT_QUESTION_BLOCK_PATTERN = Pattern.compile(
            "(?is)\\[\\[NEXT_QUESTION\\]\\](.*?)\\[\\[/NEXT_QUESTION\\]\\]"
    );
    private static final String NEXT_QUESTION_START = "[[NEXT_QUESTION]]";
    private static final String NEXT_QUESTION_PROTOCOL = """

                # Quick reply quality rules
                - Ask only one follow-up question. Choose the missing axis that matters most now: incident, relationship, timing, evidence, impact/emotional state, safety, or desired help.
                - Do not keep asking evidence questions when the user's latest answer was about impact, fear, daily life, relationship, timing, or desired help. Move to the next missing axis instead.
                - Do not create duplicate questions such as "what harm happened?" and "what daily difficulty happened?" for the same turn. If impact is needed, ask about the user's current emotional or daily-life state caused by the incident.
                - Each option message must read like a natural first-person user message. Never end option labels or messages with "~함", "라고 함", or report-note style wording.
                - Option labels should be short, but the option message should be complete enough to send as the user's chat message.
                - If the user already answered an axis in the visible conversation, do not ask that same axis again unless the answer was unclear or contradictory.

                # 다음 확인 질문 출력 규칙
                - 답변 본문이 끝난 뒤 반드시 아래 블록을 붙이세요. 이 블록은 화면 카드로만 쓰이고 사용자에게 본문으로 보이지 않습니다.
                - 블록은 반드시 닫는 태그 [[/NEXT_QUESTION]]까지 완전하게 출력하세요. 선택지를 본문에 섞거나 닫는 태그를 줄여 쓰지 마세요.
                - 지금 맥락에서 가장 자연스럽고 필요한 사실 확인 질문 1개만 만드세요. 고정 설문 문구를 베끼지 말고, 사용자가 이미 말한 사실은 다시 묻지 마세요.
                - 질문은 학교폭력 유형을 고르게 하는 것이 아니라 관찰 가능한 사실을 답하게 해야 합니다.
                - 단체 채팅방·단톡방 첫 확인은 선생님/어른이 방에 있는지 묻지 말고, 언제부터 얼마나 자주 있었는지 또는 몇 명이 보거나 참여했는지를 먼저 확인하세요.
                - 선택지는 2~4개로 만들고, 사용자가 쉽게 누를 수 있는 사실 답변이어야 합니다. 직접 입력은 화면에 항상 있으므로 선택지에 억지로 넣지 않아도 됩니다.
                - 더 물을 실익보다 정리 실익이 크거나 사용자가 리포트를 직접 요청한 상황이면 질문에 "없음"이라고 쓰세요.

                [[NEXT_QUESTION]]
                질문: 지금 확인할 핵심 질문 1개 또는 없음
                선택지:
                - 짧은 선택지 | 확인 답변: 사용자가 이 선택지를 골랐을 때 상담 기록에 남길 자연스러운 문장
                - 짧은 선택지 | 확인 답변: 사용자가 이 선택지를 골랐을 때 상담 기록에 남길 자연스러운 문장
                [[/NEXT_QUESTION]]
                """;

    private record PromptContext(String prompt, String lawContext, ReplyMode mode) {}
    private record ConfirmationCandidate(String id, String question, List<Map<String, String>> options) {}
    private record GeneratedReply(String reply, ConfirmationCandidate followUpQuestion) {}

    private static final Pattern LAW_HEADER_PATTERN =
            Pattern.compile("^===\\s*(.+?)\\s*===$");
    private static final Pattern ARTICLE_REFERENCE_PATTERN = Pattern.compile(
            "제\\s*(\\d+)\\s*조\\s*의\\s*(\\d+)"
                    + "|제\\s*(\\d+)\\s*의\\s*(\\d+)\\s*조"
                    + "|제\\s*(\\d+)\\s*조"
    );
    private static final List<String> KNOWN_LAW_MARKERS = List.of(
            "학교폭력 예방 및 대책에 관한 법률",
            "학교폭력예방법",
            "형법",
            "소년법",
            "정보통신망 이용촉진 및 정보보호 등에 관한 법률",
            "정보통신망법",
            "성폭력범죄의 처벌 등에 관한 특례법",
            "성폭력처벌법",
            "아동·청소년의 성보호에 관한 법률",
            "아청법",
            "아동복지법",
            "초·중등교육법",
            "초중등교육법",
            "민법",
            "스토킹범죄의 처벌 등에 관한 법률",
            "스토킹처벌법"
    );

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final ReportRepository reportRepository;
    private final LawApiService lawApiService;
    private final AnalysisService analysisService;
    private final ReportService reportService;

    @Value("${groq.api-key:}")
    private String groqApiKey;

    @Value("${groq.model:llama-3.1-8b-instant}")
    private String groqModel;

    @Value("${groq.max-completion-tokens:900}")
    private int groqMaxCompletionTokens;

    @Value("${gemini.api-key:}")
    private String geminiApiKey;

    @Value("${ai.backup-key:}")
    private String backupApiKey;

    @Value("${deepseek.api-key:}")
    private String deepSeekApiKey;

    @Value("${deepseek.model:deepseek-v4-flash}")
    private String deepSeekModel;

    @Value("${deepseek.max-tokens:900}")
    private int deepSeekMaxTokens;

    private final RestTemplate aiClient;
    private volatile long groqDisabledUntil = 0;
    private volatile long geminiDisabledUntil = 0;
    private volatile long deepSeekDisabledUntil = 0;
    private volatile String lastProvider = "none";
    private volatile String lastProviderFailure = "";
    private volatile String lastProviderFailureReason = "";

    public ChatService(SessionRepository sessionRepository, MessageRepository messageRepository,
                       ReportRepository reportRepository,
                       LawApiService lawApiService, AnalysisService analysisService,
                       ReportService reportService) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.reportRepository = reportRepository;
        this.lawApiService = lawApiService;
        this.analysisService = analysisService;
        this.reportService = reportService;
        CloseableHttpClient httpClient = HttpClients.custom()
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(8))
                        .setResponseTimeout(Timeout.ofSeconds(35))
                        .build())
                .build();
        this.aiClient = new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient));
    }

    public Session createSession(User user) {
        Session session = new Session();
        session.setUser(user);
        return sessionRepository.save(session);
    }

    public Map<String, Object> sendMessage(User user, Long sessionId, String content) {
        String normalized = content == null ? "" : content.trim();
        if (normalized.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "상담 내용을 입력해 주세요.");
        }
        if (normalized.length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "상담 내용은 2000자 이하로 입력해 주세요.");
        }

        Session requestedSession = resolveSession(user, sessionId);
        boolean newSessionStarted = false;
        Session session = requestedSession;

        List<Message> existingHistory = messageRepository.findBySessionOrderByCreatedAtAsc(session);
        if (isConversationStopped(existingHistory)) {
            ReportReadiness readiness = assessReadiness(existingHistory);
            return stoppedResponse(
                    session,
                    buildConversationStoppedReply("이미 중단된 상담입니다. 새 상담에서 다시 시작해야 합니다."),
                    messageRepository.countBySessionAndRole(session, "user"),
                    readiness
            );
        }

        Message userMessage = new Message();
        userMessage.setSession(session);
        userMessage.setRole("user");
        userMessage.setContent(normalized);
        messageRepository.save(userMessage);

        List<Message> history = messageRepository.findBySessionOrderByCreatedAtAsc(session);
        ReportReadiness readiness = assessReadiness(history);
        if (shouldGuardIrrelevantInput(normalized, hasConversationContext(history), isConversationalFollowUp(normalized, history))) {
            String reply = buildConversationStoppedReply(stopReasonForInput(normalized));
            Message aiMessage = new Message();
            aiMessage.setSession(session);
            aiMessage.setRole("assistant");
            aiMessage.setContent(reply);
            messageRepository.save(aiMessage);
            return stoppedResponse(
                    session,
                    reply,
                    messageRepository.countBySessionAndRole(session, "user"),
                    readiness
            );
        }

        GeneratedReply generatedReply = getAiReply(history);
        List<Map<String, Object>> confirmationPrompts = confirmationPrompts(readiness, history, generatedReply.followUpQuestion());
        boolean explicitReportRequest = isExplicitReportRequest(normalized);
        Map<String, Object> report = null;
        boolean reportGenerated = false;
        boolean reportUpdated = false;
        boolean canGenerateReport = canGenerateReportFromExplicitRequest(
                readiness,
                confirmationPrompts,
                history,
                explicitReportRequest
        );
        if (canGenerateReport) {
            ReportService.ReportMutation mutation = reportService.generateOrUpdateForSessionMutation(user, session, "", readiness);
            report = mutation.report();
            reportGenerated = mutation.generated();
            reportUpdated = mutation.updated();
        }
        String reply = appendReportSummary(
                appendReportGateNotice(
                        connectReplyToConfirmation(generatedReply.reply(), confirmationPrompts),
                        explicitReportRequest,
                        reportGenerated || reportUpdated,
                        readiness,
                        history
                ),
                report,
                reportGenerated,
                reportUpdated
        );

        Message aiMessage = new Message();
        aiMessage.setSession(session);
        aiMessage.setRole("assistant");
        aiMessage.setContent(reply);
        messageRepository.save(aiMessage);
        long userMessageCount = messageRepository.countBySessionAndRole(session, "user");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("session_id", session.getId());
        response.put("reply", reply);
        response.put("user_message_count", userMessageCount);
        response.put("new_session_started", newSessionStarted);
        response.put("report_generated", reportGenerated);
        response.put("report_updated", reportUpdated);
        response.put("report", report);
        response.put("report_ready", reportGenerated || reportUpdated || shouldSuggestReport(readiness, history));
        response.put("report_requested", explicitReportRequest);
        response.put("report_suggested", shouldSuggestReport(readiness, history));
        response.put("report_status", readiness.status());
        response.put("report_reason", readiness.reason());
        response.put("missing_info", readiness.missingInfo());
        response.put("confirmation_prompts", confirmationPrompts);
        putCounselingMetadata(response, readiness, history, confirmationPrompts);
        response.put("conversation_stopped", false);
        return response;
    }

    public Map<String, Object> sendGuestMessage(String content, List<HistoryMessage> clientHistory) {
        String normalized = content == null ? "" : content.trim();
        if (normalized.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "상담 내용을 입력해 주세요.");
        }
        if (normalized.length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "상담 내용은 2000자 이하로 입력해 주세요.");
        }

        List<Message> history = guestHistory(clientHistory, normalized);
        ReportReadiness readiness = assessReadiness(history);
        if (isConversationStopped(history) || shouldGuardIrrelevantInput(normalized, hasConversationContext(history), isConversationalFollowUp(normalized, history))) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("session_id", null);
            response.put("reply", buildConversationStoppedReply(stopReasonForInput(normalized)));
            response.put("user_message_count", userMessageCount(history));
            response.put("new_session_started", false);
            response.put("report_ready", false);
            response.put("report_status", "대화 중단");
            response.put("report_reason", stopReasonForInput(normalized));
            response.put("missing_info", List.of());
            response.put("confirmation_prompts", List.of());
            response.put("conversation_stopped", true);
            response.put("temporary", true);
            return response;
        }
        GeneratedReply generatedReply = getAiReply(history);
        List<Map<String, Object>> confirmationPrompts = confirmationPrompts(readiness, history, generatedReply.followUpQuestion());
        boolean explicitReportRequest = isExplicitReportRequest(normalized);
        Map<String, Object> report = null;
        boolean reportGenerated = false;
        boolean reportUpdated = false;
        boolean canGenerateReport = shouldGenerateGuestTemporaryReport(readiness, confirmationPrompts, history, explicitReportRequest);
        if (canGenerateReport) {
            report = reportService.generateTemporary(history, "", readiness);
            reportUpdated = hasPriorReportSignal(history);
            reportGenerated = !reportUpdated;
        }
        String reply = appendReportSummary(
                appendReportGateNotice(
                        connectReplyToConfirmation(generatedReply.reply(), confirmationPrompts),
                        explicitReportRequest,
                        reportGenerated || reportUpdated,
                        readiness,
                        history
                ),
                report,
                reportGenerated,
                reportUpdated
        );

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("session_id", null);
        response.put("reply", reply);
        response.put("user_message_count", userMessageCount(history));
        response.put("new_session_started", false);
        response.put("report_generated", reportGenerated);
        response.put("report_updated", reportUpdated);
        response.put("report", report);
        response.put("report_ready", reportGenerated || reportUpdated || shouldSuggestReport(readiness, history));
        response.put("report_requested", explicitReportRequest);
        response.put("report_suggested", shouldSuggestReport(readiness, history));
        response.put("report_status", readiness.status());
        response.put("report_reason", readiness.reason());
        response.put("missing_info", readiness.missingInfo());
        response.put("confirmation_prompts", confirmationPrompts);
        putCounselingMetadata(response, readiness, history, confirmationPrompts);
        response.put("conversation_stopped", false);
        response.put("temporary", true);
        return response;
    }

    static boolean shouldGenerateGuestTemporaryReport(
            ReportReadiness readiness,
            List<Map<String, Object>> confirmationPrompts,
            List<Message> history
    ) {
        return shouldGenerateGuestTemporaryReport(readiness, confirmationPrompts, history, false);
    }

    static boolean shouldGenerateGuestTemporaryReport(
            ReportReadiness readiness,
            List<Map<String, Object>> confirmationPrompts,
            List<Message> history,
            boolean explicitReportRequest
    ) {
        return canGenerateReportFromExplicitRequest(readiness, confirmationPrompts, history, explicitReportRequest);
    }

    static boolean canGenerateReportFromExplicitRequest(
            ReportReadiness readiness,
            List<Map<String, Object>> confirmationPrompts,
            List<Message> history,
            boolean explicitReportRequest
    ) {
        if (!explicitReportRequest) return false;
        if (!strictReportContextReady(readiness, history)) return false;
        return confirmationPrompts == null || confirmationPrompts.isEmpty() || hasPriorReportSignal(history);
    }

    public List<Map<String, Object>> getMessages(Long sessionId, User user) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "상담 세션을 찾을 수 없습니다."));
        requireOwner(user, session);

        return messageRepository.findBySessionOrderByCreatedAtAsc(session).stream()
                .map(ChatService::messageToMap)
                .toList();
    }

    public Map<String, Object> getSessionDetail(Long sessionId, User user) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "상담 세션을 찾을 수 없습니다."));
        requireOwner(user, session);

        List<Message> messages = messageRepository.findBySessionOrderByCreatedAtAsc(session);
        ReportReadiness readiness = assessReadiness(messages);
        return Map.of(
                "session_id", session.getId(),
                "messages", messages.stream().map(ChatService::messageToMap).toList(),
                "readiness", readinessToMap(readiness, messages)
        );
    }

    public Map<String, Object> getReadiness(Long sessionId, User user) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "상담 세션을 찾을 수 없습니다."));
        requireOwner(user, session);

        List<Message> messages = messageRepository.findBySessionOrderByCreatedAtAsc(session);
        ReportReadiness readiness = assessReadiness(messages);
        return readinessToMap(readiness, messages);
    }

    @Transactional
    public void deleteSession(Long sessionId, User user) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "상담 세션을 찾을 수 없습니다."));
        requireOwner(user, session);

        reportRepository.deleteBySession(session);
        messageRepository.deleteBySession(session);
        sessionRepository.delete(session);
    }

    private static Map<String, Object> messageToMap(Message message) {
        return Map.of(
                "id", message.getId(),
                "role", message.getRole(),
                "content", message.getContent(),
                "created_at", message.getCreatedAt().toString()
        );
    }

    private Map<String, Object> readinessToMap(ReportReadiness readiness, List<Message> history) {
        boolean stopped = isConversationStopped(history);
        List<Map<String, Object>> prompts = stopped ? List.of() : confirmationPrompts(readiness, history);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ready", !stopped && shouldSuggestReport(readiness, history));
        response.put("status", stopped ? "대화 중단" : readiness.status());
        response.put("reason", stopped ? "학교폭력 상담과 무관한 입력으로 상담이 중단되었습니다." : readiness.reason());
        response.put("missing_info", stopped ? List.of() : readiness.missingInfo());
        response.put("confirmation_prompts", prompts);
        response.put("school_violence_likely", !stopped && readiness.schoolViolenceLikely());
        response.put("conversation_stopped", stopped);
        if (!stopped) {
            putCounselingMetadata(response, readiness, history, prompts);
        }
        return response;
    }

    private Map<String, Object> stoppedResponse(Session session, String reply, long userMessageCount, ReportReadiness readiness) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("session_id", session.getId());
        response.put("reply", reply);
        response.put("user_message_count", userMessageCount);
        response.put("new_session_started", false);
        response.put("report_updated", false);
        response.put("report_ready", false);
        response.put("report_status", "대화 중단");
        response.put("report_reason", "학교폭력 상담과 무관한 입력으로 상담이 중단되었습니다.");
        response.put("missing_info", readiness == null ? List.of() : readiness.missingInfo());
        response.put("confirmation_prompts", List.of());
        response.put("conversation_stopped", true);
        return response;
    }

    private void putCounselingMetadata(
            Map<String, Object> response,
            ReportReadiness readiness,
            List<Message> history,
            List<Map<String, Object>> confirmationPrompts
    ) {
        Map<String, Object> metadata = buildCounselingMetadata(readiness, history, confirmationPrompts);
        response.put("counseling_state", metadata);
        response.put("counseling_stage", metadata.get("stage"));
        response.put("case_understanding", metadata.get("case_understanding"));
        response.put("next_question", metadata.get("next_question"));
    }

    private Map<String, Object> buildCounselingMetadata(
            ReportReadiness readiness,
            List<Message> history,
            List<Map<String, Object>> confirmationPrompts
    ) {
        String combined = combinedUserText(history);
        List<String> types = detectTypes(combined);
        AnalysisResult analysis = combined.isBlank()
                ? null
                : analysisService.analyze(combined, readiness);
        String nextQuestion = firstConfirmationQuestion(confirmationPrompts);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("stage", counselingStage(readiness, history));
        metadata.put("case_understanding", buildCaseUnderstanding(combined, types, readiness));
        metadata.put("possible_types", types);
        metadata.put("legal_issues", legalIssueSummary(types, combined, readiness));
        metadata.put("immediate_evidence", analysis == null ? List.of() : analysis.evidenceGuide().stream().limit(4).toList());
        metadata.put("next_question", nextQuestion);
        metadata.put("dimensions", analysisService.assessCounselingDimensions(combined, readiness, userMessageCount(history)));
        metadata.put("report_suggested", shouldSuggestReport(readiness, history));
        return metadata;
    }

    private static String counselingStage(ReportReadiness readiness, List<Message> history) {
        if (readiness == null) return "상담 방향 확인";
        long userMessages = userMessageCount(history);
        if (!readiness.schoolViolenceLikely() && readiness.ready()) return "학교폭력 해당성 검토";
        if (!readiness.ready()) {
            if (userMessages <= 2) return "사건 구조 파악";
            return "핵심 맥락 보완";
        }
        if (shouldSuggestReport(readiness, history)) return "상담 내용 정리 가능";
        return "상담 깊이 보강";
    }

    private static String buildCaseUnderstanding(String combined, List<String> types, ReportReadiness readiness) {
        if (combined == null || combined.isBlank()) {
            return "아직 사건 내용이 충분히 확인되지 않았습니다.";
        }
        String typeText = types == null || types.isEmpty()
                ? "학교폭력 유형은 아직 특정 전"
                : String.join(", ", types) + " 가능성";
        String status = readiness == null ? "상담 내용을 더 확인하는 중입니다." : readiness.reason();
        return typeText + "을 중심으로 보고 있으며, " + status;
    }

    private static List<String> legalIssueSummary(List<String> types, String text, ReportReadiness readiness) {
        List<String> issues = new ArrayList<>();
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (types == null || types.isEmpty()) {
            issues.add("구체적 행동과 학교 관계가 확인되면 학교폭력 해당성과 관련 법령을 좁힐 수 있습니다.");
        } else {
            if (types.contains("언어 폭력")) issues.add("모욕·비하·소문 유포의 표현 수위와 공개성 확인이 필요합니다.");
            if (types.contains("사이버 폭력")) issues.add("게시물·단톡방의 전파 범위, 작성자 특정, 원본 보존 여부가 쟁점입니다.");
            if (types.contains("신체 폭력")) issues.add("부상 정도, 반복성, 목격자와 즉시 안전 여부가 중요합니다.");
            if (types.contains("따돌림")) issues.add("배제 방식, 지속 기간, 집단성, 학교 인지 여부가 쟁점입니다.");
            if (types.contains("성폭력")) issues.add("원하지 않은 성적 말·접촉의 구체성, 안전 확보, 상담 연결이 우선입니다.");
            if (types.contains("스토킹")) issues.add("반복 접근·연락 기록과 접근 차단 이후의 지속 여부가 중요합니다.");
            if (types.contains("갈취")) issues.add("요구 금액·물건, 강요 방식, 결제·전달 기록이 쟁점입니다.");
        }
        if (isPerpetratorText(t)) {
            issues.add("본인이 한 행동의 중단, 삭제 전 원본 보존, 사과와 피해 회복 가능성이 핵심입니다.");
        }
        if (readiness != null && !readiness.schoolViolenceLikely()) {
            issues.add("학교 관계가 약하면 학교폭력 절차보다 일반 신고·플랫폼 신고 경로가 더 맞을 수 있습니다.");
        }
        return issues.stream().distinct().limit(5).toList();
    }

    private List<Map<String, Object>> confirmationPrompts(ReportReadiness readiness, List<Message> history) {
        return confirmationPrompts(readiness, history, null);
    }

    private List<Map<String, Object>> confirmationPrompts(
            ReportReadiness readiness,
            List<Message> history,
            ConfirmationCandidate aiFollowUpQuestion
    ) {
        String combined = combinedUserText(history);
        if (needsRealityCheck(combined)) return List.of(confirmationPrompt(realityCheckQuestion()));
        if (readiness.ready() || readiness.missingInfo().isEmpty()) return List.of();
        List<ConfirmationCandidate> priorityCandidates = priorityConfirmationCandidates(combined);
        if (!priorityCandidates.isEmpty()) {
            return priorityCandidates.stream()
                    .limit(1)
                    .map(ChatService::confirmationPrompt)
                    .toList();
        }
        List<ConfirmationCandidate> aiCandidates = filterUsableCandidates(
                aiFollowUpQuestion == null ? List.of() : List.of(aiFollowUpQuestion),
                combined,
                history
        );
        if (!aiCandidates.isEmpty()) {
            return aiCandidates.stream()
                    .limit(1)
                    .map(ChatService::confirmationPrompt)
                    .toList();
        }
        return confirmationCandidates(readiness, combined, userMessageCount(history), history).stream()
                .limit(1)
                .map(ChatService::confirmationPrompt)
                .toList();
    }

    private static List<ConfirmationCandidate> priorityConfirmationCandidates(String combinedText) {
        String text = combinedText == null ? "" : combinedText;
        if (!hasChatSignal(text)) return List.of();
        if (!hasGroupChatTimelineDetailAnswer(text)) return List.of(groupChatTimelineQuestion());
        if (!hasGroupChatScaleAnswer(text)) return List.of(groupChatScaleQuestion());
        return List.of();
    }

    private static Map<String, Object> confirmationPrompt(ConfirmationCandidate candidate) {
        Map<String, Object> prompt = new LinkedHashMap<>();
        prompt.put("id", candidate.id());
        prompt.put("purpose", confirmationPurpose(candidate.id()));
        prompt.put("question", candidate.question());
        prompt.put("instruction", confirmationInstruction(candidate.id()));
        prompt.put("options", candidate.options());
        return prompt;
    }

    static String connectReplyToConfirmation(String reply, List<Map<String, Object>> prompts) {
        if (reply == null) return "";
        if (prompts == null || prompts.isEmpty()) {
            return stripQuickReplyLeadIns(stripGeneratedQuestionSentences(reply)).trim();
        }
        String trimmed = stripQuickReplyLeadIns(stripGeneratedQuestionSentences(reply)).trim();
        trimmed = stripDanglingConfirmationLeadIn(trimmed);
        if (trimmed.isBlank()) {
            trimmed = "말해준 내용은 상담 기록에 반영했습니다.";
        }
        if (trimmed.contains("아래 빠른 답변")
                || trimmed.contains("아래 확인 카드")
                || trimmed.contains("아래 선택지")
                || (trimmed.contains("아래에") && trimmed.contains("띄워둘게요"))) {
            return trimmed;
        }
        String question = firstConfirmationQuestion(prompts);
        if (question.isBlank() || trimmed.contains(question)) return trimmed;
        if (trimmed.length() > 520) return trimmed;
        return trimmed + "\n\n필요하면 아래 빠른 답변으로 이어갈 수 있어요.";
    }

    private static String appendReportSummary(
            String reply,
            Map<String, Object> report,
            boolean reportGenerated,
            boolean reportUpdated
    ) {
        if (report == null || (!reportGenerated && !reportUpdated)) return reply;
        String summary = reportSummaryText(report, reportGenerated ? "생성" : "갱신");
        String trimmed = reply == null ? "" : reply.trim();
        if (trimmed.contains("리포트가 생성되었습니다") || trimmed.contains("리포트가 갱신되었습니다")) {
            return trimmed;
        }
        if (isGenericRecordOnlyReply(trimmed)) {
            return summary;
        }
        return trimmed.isBlank() ? summary : trimmed + "\n\n" + summary;
    }

    private static String reportSummaryText(Map<String, Object> report, String action) {
        String status = stringValue(report.get("assessment_status"), "판단 정보 없음");
        String risk = stringValue(report.get("risk_score"), "0");
        String types = reportTypesText(report.get("violence_types"));
        String caseSummary = reportCaseSummary(report);
        String summaryLine = caseSummary.isBlank() ? "" : "\n사건 정리: " + caseSummary;
        return "리포트가 %s되었습니다. 현재 판단: %s, 유형: %s, 위험도: %s/10입니다.%s\n필요하면 이후에도 상담을 이어가며 새로 떠오른 사실을 더 보강할 수 있습니다."
                .formatted(action, status, types, risk, summaryLine);
    }

    private static String reportCaseSummary(Map<String, Object> report) {
        Object details = report.get("assessment_details");
        if (details instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                String text = stringValue(item, "");
                if (text.startsWith("핵심 상황:")) {
                    return text.substring("핵심 상황:".length()).trim();
                }
            }
        }
        String summary = stringValue(report.get("summary"), "");
        int marker = summary.indexOf("분류 유형은");
        return marker > 0 ? summary.substring(0, marker).trim() : summary;
    }

    private static String stringValue(Object value, String fallback) {
        if (value == null) return fallback;
        String text = String.valueOf(value).trim();
        return text.isBlank() ? fallback : text;
    }

    private static String reportTypesText(Object value) {
        if (value instanceof List<?> list && !list.isEmpty()) {
            return list.stream()
                    .map(String::valueOf)
                    .filter(item -> !item.isBlank())
                    .collect(Collectors.joining(", "));
        }
        return "분류 정보 없음";
    }

    private static boolean isGenericRecordOnlyReply(String reply) {
        if (reply == null || reply.isBlank()) return true;
        String text = reply.replace("\r", "").trim();
        return containsAny(text,
                "방금 답변은 상담 기록에 반영했습니다",
                "이 내용도 상담 기록에 반영해 두겠습니다",
                "필요한 내용은 이어지는 대화 안에서 자연스럽게 더 보완하면 됩니다")
                && text.length() <= 160;
    }

    private static boolean hasPriorReportSignal(List<Message> history) {
        if (history == null) return false;
        return history.stream()
                .filter(Objects::nonNull)
                .filter(message -> "assistant".equals(message.getRole()))
                .map(Message::getContent)
                .filter(Objects::nonNull)
                .anyMatch(content -> content.contains("리포트가 생성되었습니다")
                        || content.contains("리포트가 갱신되었습니다")
                        || content.contains("리포트 보기 버튼"));
    }

    static boolean shouldSuggestReport(ReportReadiness readiness, List<Message> history) {
        return strictReportContextReady(readiness, history);
    }

    private static boolean strictReportContextReady(ReportReadiness readiness, List<Message> history) {
        if (readiness == null || !readiness.ready() || !readiness.missingInfo().isEmpty()) return false;
        if (isConversationStopped(history)) return false;

        String text = combinedUserText(history);
        if (text.isBlank()) return false;
        Set<String> answeredFamilies = answeredQuestionFamilies(history);
        return hasStrictReportAnswers(text, answeredFamilies) || hasStrictReportKeyFacts(readiness.keyFacts());
    }

    private static boolean hasStrictReportAnswers(String text, Set<String> answeredFamilies) {
        return (hasReportAnswer("incident", text, answeredFamilies)
                || hasViolenceTypeSignal(text)
                || hasBodilyWasteIncidentSignal(text))
                && hasReportAnswer("relationship", text, answeredFamilies)
                && hasReportAnswer("timeline", text, answeredFamilies)
                && hasReportAnswer("evidence", text, answeredFamilies)
                && hasReportAnswer("impact", text, answeredFamilies)
                && hasReportAnswer("goal", text, answeredFamilies);
    }

    private static boolean hasStrictReportKeyFacts(List<String> keyFacts) {
        if (keyFacts == null || keyFacts.isEmpty()) return false;
        String facts = String.join(" ", keyFacts);
        return containsAny(facts, "구체적인 사건", "사건 내용")
                && containsAny(facts, "상대방과 학교 관계", "상대방 특정", "학교 관계", "상대 관계")
                && containsAny(facts, "시점", "반복성")
                && containsAny(facts, "증거", "발생 경로")
                && containsAny(facts, "피해 영향", "회복 노력", "보호 필요")
                && containsAny(facts, "요청한 도움", "도움 방향", "원하는 도움");
    }

    private static String appendReportGateNotice(
            String reply,
            boolean explicitReportRequest,
            boolean reportChanged,
            ReportReadiness readiness,
            List<Message> history
    ) {
        String trimmed = reply == null ? "" : reply.trim();
        if (!explicitReportRequest || reportChanged || strictReportContextReady(readiness, history)) return trimmed;
        String notice = "리포트는 사건 내용, 관계, 시점, 증거, 피해 영향, 원하는 도움까지 확인된 뒤 생성할게요.";
        if (trimmed.contains(notice)) return trimmed;
        return trimmed.isBlank() ? notice : trimmed + "\n\n" + notice;
    }

    private static boolean isExplicitReportRequest(String text) {
        String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (t.isBlank()) return false;
        boolean reportWord = containsAny(t, "리포트", "보고서", "결과", "문서", "정리");
        boolean actionWord = containsAny(t, "만들", "생성", "보여", "열어", "작성", "정리해", "정리해줘", "정리해 주세요", "뽑아", "출력");
        if (reportWord && actionWord) return true;
        return containsAny(t,
                "리포트 만들어줘", "리포트 만들어 주세요", "리포트 생성해줘", "리포트 보여줘",
                "분석 리포트", "결과 보여줘", "결과 보여 주세요",
                "지금까지 내용 정리해줘", "상담 내용 정리해줘", "상담 내용을 정리해줘",
                "이 내용으로 정리해줘", "이 내용 정리해줘");
    }

    private static String stripDanglingConfirmationLeadIn(String reply) {
        if (reply == null || reply.isBlank()) return "";
        String cleaned = reply.replaceAll("((다음으로\\s+)?하나만\\s+더\\s+확인할게요|확인을\\s+위해\\s+질문\\s+하나\\s+할게요)\\.\\s*", "");
        return cleaned.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .collect(Collectors.joining("\n"))
                .trim();
    }

    private static String firstConfirmationQuestion(List<Map<String, Object>> prompts) {
        if (prompts == null || prompts.isEmpty()) return "";
        Object question = prompts.get(0).get("question");
        return question == null ? "" : String.valueOf(question).trim();
    }

    private static String stripGeneratedQuestionSentences(String reply) {
        if (reply == null || reply.isBlank()) return "";
        return reply.lines()
                .map(ChatService::stripQuestionFragments)
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .collect(Collectors.joining("\n"))
                .trim();
    }

    private static String stripQuickReplyLeadIns(String reply) {
        if (reply == null || reply.isBlank()) return "";
        return reply.lines()
                .map(line -> line
                        .replaceAll("\\s*필요하면\\s*아래\\s*(빠른 답변|확인 카드|선택지)[^\\n]*", "")
                        .replaceAll("\\s*아래\\s*(빠른 답변|확인 카드|선택지)[^\\n]*", "")
                        .replaceAll("\\s*빠른 답변으로\\s*이어갈\\s*수\\s*있어요\\.?\\s*$", ""))
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .collect(Collectors.joining("\n"))
                .trim();
    }

    private static String stripQuestionFragments(String line) {
        String result = removeUncontrolledQuestionSentences(line);
        while (result.contains("?")) {
            int question = result.indexOf('?');
            int start = Math.max(
                    Math.max(result.lastIndexOf('.', question), result.lastIndexOf('!', question)),
                    result.lastIndexOf('。', question)
            );
            String before = start >= 0 ? result.substring(0, start + 1) : "";
            String after = question + 1 < result.length() ? result.substring(question + 1) : "";
            result = (before + " " + after).replaceAll("\\s{2,}", " ").trim();
        }
        return result;
    }

    private static String confirmationPurpose(String id) {
        if (id == null) return "방금 상담 내용과 이어지는 핵심 정보를 확인하기 위한 것입니다.";
        if (id.startsWith("incident")) {
            return "방금 말한 일을 어떤 유형으로 볼지 정확히 잡기 위한 확인입니다.";
        }
        if (id.startsWith("reality_check")) {
            return "표현이 특이하거나 비유일 수 있어 실제 사건인지 먼저 확인하기 위한 것입니다.";
        }
        if (id.startsWith("relationship")) {
            return "학교폭력 절차 적용 가능성을 보려면 상대와의 학교 관계가 필요합니다.";
        }
        if (id.startsWith("timeline")) {
            return "반복성, 지속성, 지금도 계속되는지에 따라 대응 우선순위가 달라져서 묻는 것입니다.";
        }
        if (id.startsWith("evidence") || id.startsWith("post_trace")) {
            return "리포트 신뢰도를 높이려면 지금 남아 있는 자료가 무엇인지 확인해야 합니다.";
        }
        if (id.startsWith("impact") || id.startsWith("physical_injury") || id.startsWith("physical_support")
                || id.startsWith("physical_context")) {
            return "보호 조치와 다음 행동의 우선순위를 정하려면 현재 영향과 안전 상태가 필요합니다.";
        }
        if (id.startsWith("goal")) {
            return "원하는 도움에 맞춰 신고, 증거 정리, 안전 확보 중 방향을 좁히기 위한 확인입니다.";
        }
        if (id.startsWith("final_check")) {
            return "지금까지의 내용이 하나의 같은 사안인지 확인해야 리포트가 섞이지 않습니다.";
        }
        if (id.startsWith("actor")) {
            return "본인이 한 행동을 멈추고 피해 회복 방향을 정리하기 위한 확인입니다.";
        }
        if (id.startsWith("chat_pattern")) {
            return "단체 채팅방에서는 누가 주도했는지와 반복 방식이 판단에 중요해서 확인하는 것입니다.";
        }
        if (id.startsWith("chat_timeline")) {
            return "단체 채팅방 사안은 시작 시점과 반복 빈도에 따라 대응 우선순위가 달라져서 확인하는 것입니다.";
        }
        if (id.startsWith("chat_scale")) {
            return "단체 채팅방에서 몇 명이 보거나 참여했는지는 공개성과 피해 규모를 판단하는 데 중요합니다.";
        }
        if (id.startsWith("chat_support")) {
            return "보복 걱정과 안전 조치를 보려면 보호자나 학교에 공유됐는지 확인해야 합니다.";
        }
        if (id.startsWith("post_spread")) {
            return "게시물 공개 범위와 퍼진 정도에 따라 증거 확보와 신고 순서가 달라집니다.";
        }
        return "방금 상담 내용과 이어지는 핵심 정보를 확인하기 위한 것입니다.";
    }

    private static String confirmationInstruction(String id) {
        if (id != null && id.startsWith("final_check")) {
            return "맞으면 첫 선택지를 누르고, 빠진 내용이 있으면 직접 입력으로 보완해 주세요.";
        }
        if (id != null && id.startsWith("reality_check")) {
            return "실제 사건이면 첫 선택지를 누르고, 비유나 장난 표현이면 그에 맞는 선택지를 골라주세요.";
        }
        return "선택지는 보조용입니다. 직접 입력해도 괜찮습니다.";
    }

    static List<String> previewConfirmationQuestions(ReportReadiness readiness, String combinedText, long userMessageCount) {
        return confirmationCandidates(readiness, combinedText, userMessageCount, List.of()).stream()
                .map(ConfirmationCandidate::question)
                .toList();
    }

    static List<String> previewConfirmationQuestions(ReportReadiness readiness, List<Message> history) {
        return confirmationCandidates(readiness, combinedUserText(history), userMessageCount(history), history).stream()
                .map(ConfirmationCandidate::question)
                .toList();
    }

    private static List<ConfirmationCandidate> confirmationCandidates(
            ReportReadiness readiness,
            String combinedText,
            long userMessageCount,
            List<Message> history
    ) {
        if (readiness == null || readiness.ready()) return List.of();
        String text = combinedText == null ? "" : combinedText;
        if (isGreetingOnly(text)) return List.of();
        if (needsRealityCheck(text)) return List.of(realityCheckQuestion());
        List<ConfirmationCandidate> candidates = new ArrayList<>();

        if (needsVagueIncidentDetail(text)) {
            candidates.add(incidentQuestion(text));
            return candidates;
        }

        if (hasRoleConflict(text) && !hasRoleConflictAnswer(text)) {
            candidates.add(roleConflictQuestion());
            return candidates;
        }

        if (needsSexualIncidentDetail(text)) {
            candidates.add(incidentQuestion(text));
        }

        for (String missingInfo : readiness.missingInfo()) {
            String info = missingInfo == null ? "" : missingInfo;
            if (containsAny(info, "무슨 일", "사건 내용", "사안 내용", "행동", "구체")) {
                candidates.add(incidentQuestion(text));
            } else if (containsAny(info, "실제 사건", "실제 여부", "비유", "농담")) {
                candidates.add(realityCheckQuestion());
            } else if (containsAny(info, "학교 관계", "상대", "관계")) {
                candidates.add(relationshipQuestion(text));
            } else if (containsAny(info, "언제", "시점", "횟수", "반복", "기간", "빈도")) {
                candidates.add(timelineQuestion(text));
            } else if (containsAny(info, "증거", "자료", "단서", "기록")) {
                candidates.add(evidenceQuestion(text));
            } else if (containsAny(info, "피해 영향", "영향", "회복", "불안", "두려움", "보복")) {
                candidates.add(impactQuestion(text));
            } else if (containsAny(info, "원하는 도움", "도움", "지원", "요청", "방향")) {
                candidates.add(goalQuestion(text));
            } else if (info.contains("조금 더")) {
                candidates.addAll(deepDiveQuestions(text, userMessageCount));
            }
        }

        boolean hadCandidates = !candidates.isEmpty();
        boolean hadAnsweredCandidate = candidates.stream()
                .anyMatch(candidate -> isConfirmationCandidateAnswered(candidate.id(), text));
        candidates = filterUsableCandidates(candidates, text, history);

        if (candidates.isEmpty() && shouldUseFallbackQuestion(hadCandidates, hadAnsweredCandidate, text, readiness)) {
            List<ConfirmationCandidate> fallback = new ArrayList<>(deepDiveQuestions(text, userMessageCount));
            fallback.add(genericDetailQuestion(text));
            candidates = filterUsableCandidates(fallback, text, history);
        }
        return candidates.stream().distinct().toList();
    }

    private static List<ConfirmationCandidate> filterUsableCandidates(
            List<ConfirmationCandidate> candidates,
            String text,
            List<Message> history
    ) {
        return new ArrayList<>(candidates.stream()
                .filter(candidate -> isCandidateCompatibleWithContext(candidate, text))
                .filter(candidate -> !isConfirmationCandidateAnswered(candidate.id(), text))
                .filter(candidate -> !wasCandidateAnsweredAfterQuestion(candidate, history))
                .filter(candidate -> !wasCandidateQuestionAlreadyAsked(candidate, history))
                .toList());
    }

    private static boolean isCandidateCompatibleWithContext(ConfirmationCandidate candidate, String text) {
        if (candidate == null || candidate.id() == null) return false;
        String id = candidate.id();
        CaseDomain domain = primaryCaseDomain(text);

        if (id.startsWith("actor") || id.endsWith("_actor")) {
            return domain == CaseDomain.PERPETRATOR;
        }
        if (id.contains("sexual")) {
            return domain == CaseDomain.SEXUAL;
        }
        if (id.contains("physical")) {
            return domain == CaseDomain.PHYSICAL;
        }
        if (id.startsWith("chat_") || id.equals("evidence_chat")) {
            return domain == CaseDomain.CYBER_CHAT || hasChatSignal(text);
        }
        if (id.startsWith("post_") || id.equals("incident_post")) {
            return domain == CaseDomain.CYBER_POST || hasPostSignal(text);
        }

        if (domain == CaseDomain.SEXUAL) {
            return !candidateMentionsPhysicalViolenceOptions(candidate);
        }
        if (domain == CaseDomain.CYBER_POST && id.equals("incident")) {
            return false;
        }
        if (domain == CaseDomain.CYBER_CHAT && id.equals("incident")) {
            return false;
        }
        return true;
    }

    private static boolean candidateMentionsPhysicalViolenceOptions(ConfirmationCandidate candidate) {
        String text = (candidate.question() == null ? "" : candidate.question()) + " " +
                candidate.options().stream()
                        .map(option -> option.getOrDefault("label", "") + " " + option.getOrDefault("message", ""))
                        .collect(Collectors.joining(" "));
        return hasAny(text, "맞음", "밀침", "넘어짐", "상처", "신체 폭력", "때리거나 밀치는");
    }

    private static boolean shouldUseFallbackQuestion(
            boolean hadCandidates,
            boolean hadAnsweredCandidate,
            String text,
            ReportReadiness readiness
    ) {
        if (readiness == null || readiness.missingInfo().isEmpty()) return false;
        if (hadAnsweredCandidate) return false;
        if (hasAnsweredDeepDive(text)) return false;
        return true;
    }

    private static boolean wasCandidateAnsweredAfterQuestion(ConfirmationCandidate candidate, List<Message> history) {
        if (candidate == null || candidate.question() == null || candidate.question().isBlank() || history == null) {
            return false;
        }
        boolean asked = false;
        for (Message message : history) {
            if (message == null) continue;
            String role = message.getRole();
            String content = message.getContent() == null ? "" : message.getContent().trim();
            if ("assistant".equals(role) && content.contains(candidate.question())) {
                asked = true;
                continue;
            }
            if (asked && "user".equals(role) && !content.isBlank()) {
                return isConfirmationCandidateAnswered(candidate.id(), content)
                        || isUncertainConfirmationAnswer(content);
            }
        }
        return false;
    }

    private static boolean isUncertainConfirmationAnswer(String content) {
        String text = content == null ? "" : content.trim();
        return containsAny(text,
                "모르", "기억 안", "기억나지", "정리 어렵", "말하기 어렵", "확인하지 못",
                "특정이 어렵", "아직 확실하지", "아직 모르", "아직 못", "잘 모르");
    }

    private static boolean wasCandidateQuestionAlreadyAsked(ConfirmationCandidate candidate, List<Message> history) {
        if (candidate == null || history == null || history.isEmpty()) return false;
        String family = questionFamily(candidate.id());
        boolean asked = false;
        boolean userRespondedAfterAsk = false;
        for (Message message : history) {
            if (message == null) continue;
            String role = message.getRole();
            String content = message.getContent() == null ? "" : message.getContent().trim();
            if ("assistant".equals(role) && looksLikeSameQuestion(content, candidate, family)) {
                asked = true;
                userRespondedAfterAsk = false;
                continue;
            }
            if (asked && "user".equals(role) && !content.isBlank()) {
                userRespondedAfterAsk = isConfirmationCandidateAnswered(candidate.id(), content)
                        || isUncertainConfirmationAnswer(content);
            }
        }
        return asked && userRespondedAfterAsk;
    }

    private static String questionFamily(String id) {
        if (id == null || id.isBlank()) return "";
        if (id.startsWith("incident")) return "incident";
        if (id.startsWith("reality_check")) return "reality_check";
        if (id.startsWith("timeline")) return "timeline";
        if (id.startsWith("evidence")) return "evidence";
        if (id.startsWith("impact")) return "impact";
        if (id.startsWith("goal")) return "goal";
        if (id.startsWith("actor")) return "actor";
        if (id.startsWith("physical_context")) return "physical_context";
        if (id.startsWith("physical")) return "physical";
        if (id.startsWith("sexual")) return "sexual";
        if (id.startsWith("chat_timeline")) return "timeline";
        if (id.startsWith("chat_scale")) return "chat_scale";
        return id;
    }

    private static boolean looksLikeSameQuestion(String content, ConfirmationCandidate candidate, String family) {
        if (content == null || content.isBlank() || candidate == null) return false;
        String question = candidate.question() == null ? "" : candidate.question().trim();
        if (!question.isBlank() && content.contains(question)) return true;
        if (!looksLikeQuestionTurn(content)) return false;
        return switch (family) {
            case "incident" -> hasAny(content, "실제로 있었던 행동", "온라인에 올라온 내용", "몸에 어떤 일");
            case "relationship" -> hasAny(content, "상대와의 관계", "학교폭력 절차 기준", "같은 반", "같은 학교", "학교 밖",
                    "상대가 누구", "누가 했", "누구인지", "용의자", "특정");
            case "timeline" -> hasAny(content, "언제부터", "몇 번", "어떤 빈도", "한 번인지", "지금도");
            case "evidence" -> hasAny(content, "증거", "캡처", "원본", "대화 증거", "확인할 자료");
            case "impact" -> hasAny(content, "영향", "걱정되는 부분", "불안", "등교", "보복");
            case "goal" -> hasAny(content, "필요한 도움", "신고", "증거 정리", "안전 확보", "관계 정리");
            case "chat_scale" -> hasAny(content, "몇 명", "몇명", "참여자", "본 사람", "방에 있는 사람", "대화방 인원");
            case "chat_pattern" -> hasAny(content, "단톡방에서는", "괴롭힘이 어떤 방식", "한 명이 주도", "여러 명");
            case "chat_support" -> hasAny(content, "알고 있는 어른", "학교 담당자", "보호자", "담임");
            case "post_spread" -> hasAny(content, "공개 범위", "어느 범위", "친구들이 볼 수 있는 범위", "누가 볼 수");
            case "post_trace" -> hasAny(content, "URL", "작성자 계정", "게시 시간", "확인 가능한",
                    "누가 올렸", "작성자가 누구", "계정 주인", "용의자", "특정");
            case "physical" -> hasAny(content, "어느 부위", "목격자", "진료", "보건실", "통증");
            case "physical_context" -> hasAny(content, "피해 정도", "당시 상황", "맞거나 밀친 정도", "친구들이 알고", "상대가 흥분");
            case "sexual" -> hasAny(content, "원하지 않은 성적", "성적으로 불쾌", "신체 접촉", "접촉", "목격", "안전하게 말할");
            case "more_context" -> hasAny(content, "가장 걱정되는 부분", "지금 가장 걱정");
            case "final_check" -> hasAny(content, "하나의 같은 사안", "리포트를 생성");
            default -> false;
        };
    }

    private static boolean looksLikeQuestionTurn(String content) {
        return content.contains("?")
                || content.contains("확인을 위해 질문")
                || content.contains("골라")
                || content.contains("알려주세요")
                || content.contains("있나요")
                || content.contains("무엇인가요")
                || content.contains("어디에 가깝")
                || content.contains("어떤 방식")
                || content.contains("어느 범위")
                || content.contains("언제부터");
    }

    private static boolean isConfirmationCandidateAnswered(String id, String text) {
        if (id == null) return false;
        return switch (id) {
            case "incident", "incident_post", "incident_physical" -> hasIncidentAnswer(text);
            case "incident_sexual" -> hasSexualIncidentAnswer(text);
            case "reality_check" -> hasRealityCheckAnswer(text);
            case "relationship" -> hasRelationshipAnswer(text);
            case "timeline", "timeline_cyber" -> hasTimelineAnswer(text);
            case "evidence", "evidence_chat", "evidence_physical", "evidence_actor", "evidence_sexual", "evidence_threat" -> hasEvidenceAnswer(text);
            case "impact" -> hasImpactAnswer(text);
            case "goal" -> hasGoalAnswer(text);
            case "final_check" -> hasFinalCheckAnswer(text);
            case "role_conflict" -> hasRoleConflictAnswer(text);
            case "chat_timeline" -> hasGroupChatTimelineDetailAnswer(text);
            case "chat_scale" -> hasGroupChatScaleAnswer(text);
            case "actor_stop", "impact_actor" -> hasAny(text,
                    "완전히 중단", "문제 행동은 완전히 중단", "중단했습니다",
                    "삭제함", "삭제했습니다", "남아 있는 게시물", "남아 있는지", "아직 남아",
                    "확인하지 못", "아직 피해 회복 조치를 하지 못");
            case "actor_recovery", "goal_actor" -> hasAny(text,
                    "학교나 담임을 통해 사과", "보호자와 먼저 상의", "회복 방법",
                    "이미 상담", "사과나 피해 회복 방법", "재발 방지", "학교에 어떻게 설명");
            case "chat_pattern" -> hasAny(text,
                    "여러 명이 함께", "한 명이 주도", "초대 제외", "강퇴", "읽씹", "단체로 무시");
            case "chat_support" -> hasAny(text,
                    "보호자에게 알렸", "담임이나 학교에 알렸", "아직 어른이나 학교에 말하지 못",
                    "말하면 보복당할까");
            case "post_spread" -> hasAny(text,
                    "공개 게시물", "친구들이 볼 수 있는 범위", "댓글이나 추가 조롱", "공유되거나 퍼졌");
            case "post_trace" -> hasAny(text,
                    "게시물 url", "링크가 있습니다", "작성자 계정", "게시 시간이 보이는", "캡처만 있습니다")
                    || hasUnknownActorSignal(text);
            case "physical_injury" -> hasAny(text,
                    "팔이나 다리", "얼굴이나 머리", "몸통", "통증이 계속");
            case "physical_support" -> hasAny(text,
                    "목격자가 있습니다", "보건실이나 학교에 기록", "병원 진료를 받을 예정",
                    "진료나 목격자 확인은 없습니다");
            case "physical_context" -> hasAny(text,
                    "가볍게 밀친", "한 번 가볍게", "한 번이고 크게 다치지", "크게 다치지는",
                    "멍이나 통증이 있습니다", "여러 번 반복", "여러 명이 보거나", "친구들이 알고 있습니다",
                    "보복이나 다시 맞을까", "상대가 흥분한 상태", "상대가 흥분하거나", "분노한 상태");
            case "sexual_support" -> hasAny(text,
                    "보호자에게 말할 수", "상담교사나 담임", "117 상담", "누구에게 말해야 할지 어렵");
            case "sexual_context" -> hasAny(text,
                    "장소를 기억", "시간대를 기억", "주변에 있던 사람", "자세히 정리하기 어렵");
            case "threat_safety" -> hasAny(text,
                    "보호자에게 이미", "부모님이나 보호자", "학교 선생님께", "학교 차원의 조치",
                    "경찰 신고", "진행할 예정", "아직 보호자나 학교", "함께 대응 중");
            case "threat_school_action" -> hasAny(text,
                    "같은 반이라 즉시 분리", "등하교 동선", "동행 조치", "접근 금지",
                    "접촉 제한", "상담만 진행", "구체적인 보호 조치");
            case "more_context" -> hasImpactAnswer(text) || hasGoalAnswer(text);
            default -> false;
        };
    }

    private static boolean hasIncidentAnswer(String text) {
        return hasAny(text,
                "욕설", "비방", "모욕", "조롱", "사진", "영상", "게시물", "댓글",
                "공유", "유포", "맞거나", "가격", "밀치", "넘어뜨", "상처", "멍",
                "따돌림", "배제", "괴롭힘", "때리거나 밀치는 신체 폭력",
                "신체 접촉", "신체 폭력", "원하지 않는", "만졌", "만지는", "성적으로", "불쾌",
                "성추행", "성희롱", "배설물", "똥을", "오줌", "소변", "대변");
    }

    private static boolean needsSexualIncidentDetail(String text) {
        return hasSexualViolationSignal(text) && !hasSexualIncidentAnswer(text);
    }

    private static boolean needsVagueIncidentDetail(String text) {
        String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (t.isBlank()) return false;
        if (hasIncidentAnswer(t) || hasViolenceTypeSignal(t) || hasBodilyWasteIncidentSignal(t)) return false;
        return hasAny(t,
                "이상한 일", "이상한 짓", "나쁜 일", "안 좋은 일", "불쾌한 일", "문제되는 일",
                "무슨 일", "뭔 일", "어떤 일", "무슨 짓", "뭔 짓", "뭘 했", "뭘 당",
                "뭔가 했", "일이 있었", "당했어요", "당했습니다");
    }

    private static boolean hasSexualIncidentAnswer(String text) {
        return hasAny(text,
                "신체 접촉", "원하지 않는", "원치 않는", "만졌", "만지는", "접촉",
                "성적인 말", "성적인 농담", "성희롱", "성추행", "사진", "촬영", "유포",
                "반복적으로 다가", "반복 접근", "몸을 만", "강제로");
    }

    private static boolean hasRelationshipAnswer(String text) {
        return hasAny(text,
                "같은 반", "같은 학교", "선후배", "학원 관계", "학교 관계자가 아닙니다",
                "학교 밖", "상대가 학교 관계자인지는 아직 모르겠습니다")
                || hasUnknownActorSignal(text)
                || hasUnknownSchoolRelationshipSignal(text)
                || hasUnclearPersonalRelationshipSignal(text);
    }

    private static boolean hasTimelineAnswer(String text) {
        return hasAny(text,
                "오늘 현재까지는 한 번", "한 번 발생", "최근 비슷한 일이 여러 번", "여러 번 반복",
                "며칠 동안 반복", "몇 주 이상", "오래 지속", "지금도 계속", "정확한 시점이나 횟수",
                "기억나지 않습니다", "며칠 반복", "며칠", "몇 번", "여러 번", "계속", "반복",
                "한 달", "달 정도", "주 이상", "처음", "어제", "방금", "지난");
    }

    private static boolean hasGroupChatTimelineDetailAnswer(String text) {
        String answer = confirmationAnswerBody(text);
        String target = answer.isBlank() ? text : answer;
        return hasAny(target,
                "오늘 처음", "오늘 처음 일어난", "며칠 전부터", "며칠 동안", "며칠 이상", "몇 주",
                "한 달", "달 정도", "오래전부터", "오래 전부터", "지속적으로", "매일", "자주",
                "가끔씩", "가끔", "한 번 발생", "한 번만", "몇 번", "여러 번", "반복됐습니다",
                "반복되었습니다", "지금도 계속되고", "지금도 이어지고", "어제부터", "지난주",
                "최근 며칠", "최근 몇 주");
    }

    private static boolean hasGroupChatScaleAnswer(String text) {
        String answer = confirmationAnswerBody(text);
        String target = answer.isBlank() ? text : answer;
        return hasAny(target,
                "소수만", "2~3명", "두세 명", "몇 명", "여러 명", "반 전체", "10명 이상",
                "많은 친구", "참여자 목록", "참여자가", "방에 있던", "방에 있는", "본 사람",
                "친구들이 볼 수", "친구들이 봤", "대화방 인원");
    }

    private static boolean hasEvidenceAnswer(String text) {
        return hasAny(text,
                "캡처", "url", "링크", "참여자 목록", "보낸 시간", "앞뒤 대화",
                "대화 전체", "내용 전체", "전체 내용", "전체가 있습니다", "원본", "전체 맥락",
                "상처 사진", "진단서", "진료 기록", "목격자", "아직 확보한 증거는 없습니다",
                "아직 정리한 자료는 없습니다", "대화나 게시물 원본", "삭제나 수정한 내역",
                "상담 기록", "주변에", "본 사람", "애들이 있", "친구들이 있", "장소", "시간",
                "녹음", "통화 기록", "cctv", "CCTV", "경비 기록", "방문 기록", "메시지 기록", "반복 연락",
                "신고 접수", "접수 기록");
    }

    private static boolean hasImpactAnswer(String text) {
        return hasAny(text,
                "불안", "두려", "등교", "생활에 영향", "보복", "반복이 걱정",
                "걱정", "재발", "또 그럴", "다시 그럴", "또 쌀",
                "크게 불편한 점", "생활 영향은 없습니다", "보호자나 담임에게 일부 알렸",
                "문제 행동을 중단", "게시물이나 댓글을 삭제", "피해 회복 조치를 하지 못",
                "멍", "상처", "통증", "아프", "병원", "진단서", "진료 기록", "진료",
                "보건실", "치료", "말하기 어렵", "정리 못", "기억나지");
    }

    private static boolean hasGoalAnswer(String text) {
        return hasAny(text,
                "증거를 어떻게 정리", "신고나 학교 상담 절차", "안전하게 보호받고",
                "상대와 어떻게 거리를", "사과나 피해 회복 방법", "게시물 삭제",
                "학교에 어떻게 설명", "재발 방지", "신고 절차", "학교 상담 절차",
                "어떻게 해야", "뭘 해야", "무엇을 해야", "알고 싶", "도움 받고",
                "도움이 필요", "대처", "해결", "보호받고 싶");
    }

    private static boolean hasFinalCheckAnswer(String text) {
        return hasAny(text,
                "하나의 같은 사안", "이 내용으로 리포트를 생성", "이 내용으로 분석", "위 내용으로 리포트");
    }

    private static boolean hasRoleConflict(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return hasVictimContext(t) && isPerpetratorText(t);
    }

    private static boolean hasRoleConflictAnswer(String text) {
        return hasAny(text,
                "같은 사안에서 서로 충돌", "같은 사건 안의 충돌", "별도 사안", "새 상담으로 분리",
                "피해를 당한 입장이고 가해한 내용은 아닙니다", "제가 한 행동도 있는 사안");
    }

    private static boolean hasUnknownActorSignal(String text) {
        return hasAny(text,
                "용의자 특정", "가해자 특정", "상대 특정", "작성자 특정", "계정 특정",
                "특정이 어렵", "특정하기 어렵", "특정할 수 없", "특정 못", "특정 안",
                "누군지 모르", "누구인지 모르", "누가 했는지 모르", "누가 올렸는지 모르",
                "작성자를 모르", "작성자 모름", "계정 주인을 모르", "계정 모름", "익명",
                "모르는 계정", "모르는 사람", "알 수 없", "확인 어렵");
    }

    private static boolean hasUnknownSchoolRelationshipSignal(String text) {
        return hasAny(text,
                "학교 관계자인지는 아직 모르", "학교 관계인지 아직 모르", "학교 관계자인지 모르",
                "학교 관계 여부는 모르", "학교 관계는 모르", "학교 관계가 불명확",
                "학교 관계는 아직 모름", "학교 관계자인지는 아직 모르겠습니다");
    }

    private static boolean hasUnclearPersonalRelationshipSignal(String text) {
        return hasAny(text,
                "안 좋게 지내던 친구", "사이가 안 좋은 친구", "사이가 좋지 않은 친구",
                "알던 친구", "아는 친구", "친구입니다", "친구인 것 같습니다",
                "예전에 알던 사람", "아는 사람입니다");
    }

    private static boolean hasAnsweredDeepDive(String text) {
        return hasAny(text,
                "확인 답변: 문제 행동은 완전히 중단", "확인 답변: 남아 있던 게시물", "확인 답변: 아직 남아",
                "확인 답변: 남아 있는지 아직 확인", "확인 답변: 학교나 담임을 통해 사과",
                "확인 답변: 보호자와 먼저 상의", "확인 답변: 여러 명이 함께",
                "확인 답변: 한 명이 주도", "확인 답변: 오늘 처음 일어난", "확인 답변: 며칠 전부터",
                "확인 답변: 꽤 오래전부터", "확인 답변: 지금도 계속되고", "확인 답변: 단체 채팅방에서 소수",
                "확인 답변: 단체 채팅방에서 여러 명", "확인 답변: 반 전체에 가까운",
                "확인 답변: 대화방 인원", "확인 답변: 보호자에게 알렸",
                "확인 답변: 담임이나 학교에 알렸", "확인 답변: 공개 게시물",
                "확인 답변: 게시물 url", "확인 답변: 팔이나 다리",
                "확인 답변: 목격자가 있습니다", "답변: 친구들이 볼 수 있는 범위",
                "확인 답변: 한 번 가볍게", "확인 답변: 멍이나 통증",
                "확인 답변: 비슷한 신체 피해", "확인 답변: 여러 명이 보거나",
                "확인 답변: 당시 상대가 흥분", "확인 답변: 보복이나 다시 맞",
                "답변: 공개 게시물", "답변: 다른 사람에게 공유", "추가 설명:");
    }

    private static CaseDomain primaryCaseDomain(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (isPerpetratorText(t)) return CaseDomain.PERPETRATOR;
        if (hasSexualViolationSignal(t)) return CaseDomain.SEXUAL;
        if (hasPhysicalViolenceSignal(t) || hasBodilyWasteIncidentSignal(t)) return CaseDomain.PHYSICAL;
        if (hasChatSignal(t)) return CaseDomain.CYBER_CHAT;
        if (hasPostSignal(t)) return CaseDomain.CYBER_POST;
        if (hasSocialExclusionSignal(t)) return CaseDomain.SOCIAL_EXCLUSION;
        if (hasExtortionSignal(t)) return CaseDomain.EXTORTION;
        if (hasStalkingSignal(t)) return CaseDomain.STALKING;
        return CaseDomain.GENERAL;
    }

    private static boolean hasSexualViolationSignal(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (hasAny(t,
                "성추행", "성희롱", "성폭행", "성폭력", "성적으로", "성적인", "성희롱성", "음란",
                "성적 접촉", "성적 신체 접촉", "성적 발언", "성적 농담", "성적 모욕", "성적 욕",
                "성적 수치", "성적 불쾌", "성적 괴롭", "성적 침해")) {
            return true;
        }
        if (hasAcademicGradeSignal(t)) return false;
        if (hasAny(t, "신체 접촉", "몸을 만", "강제로 만", "만졌", "만지는", "수치심")) return true;
        return hasAny(t, "원하지 않는", "원치 않는") && hasAny(t, "접촉", "신체", "몸", "만졌", "만지는");
    }

    private static boolean hasAcademicGradeSignal(String text) {
        return hasAny(text,
                "성적에 영향", "성적 영향", "성적이 영향", "성적에도 영향",
                "성적이 떨어", "성적 떨어", "성적 하락", "성적 저하", "성적이 내려", "성적 내려",
                "성적이 낮", "성적 낮", "성적표", "시험 성적", "학교 성적", "학업 성적",
                "수업 집중", "수업에 집중", "공부", "학업", "내신", "점수", "등급");
    }

    private static boolean hasPhysicalViolenceSignal(String text) {
        return hasAny(text, "맞았", "맞고", "맞거나", "때렸", "폭행", "가격", "밀쳤", "밀침",
                "밀쳐", "넘어뜨", "멍", "상처", "통증", "다쳤", "출혈", "골절",
                "던졌", "던진", "던지", "투척", "우유곽", "물건을 던", "들이붓", "끼얹", "부었", "뿌렸");
    }

    private static boolean hasBodilyWasteIncidentSignal(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return containsAny(t, "얼굴에 똥", "몸에 똥", "똥을 쌌", "똥을 싸", "똥도 쌌", "똥도 싸",
                "똥 쌌", "똥 싸", "똥 싸질", "배설물",
                "대변", "소변", "오줌", "침을 뱉", "침 뱉");
    }

    private static boolean needsRealityCheck(String text) {
        return hasBodilyWasteIncidentSignal(text) && !hasRealityCheckAnswer(text);
    }

    private static boolean hasRealityCheckAnswer(String text) {
        String body = confirmationAnswerBody(text).toLowerCase(Locale.ROOT);
        return containsAny(body,
                "실제로 있었던 일입니다", "실제로 있었", "실제 사건", "실제 행동", "진짜 있었",
                "비유나 농담", "비유입니다", "농담입니다", "장난 표현", "장난이었습니다",
                "실제는 아닙니다", "실제로는 아닙니다");
    }

    private static boolean hasNonRealEventAnswer(String text) {
        String body = confirmationAnswerBody(text).toLowerCase(Locale.ROOT);
        return containsAny(body, "비유나 농담", "비유입니다", "농담입니다", "장난 표현",
                "장난이었습니다", "실제는 아닙니다", "실제로는 아닙니다");
    }

    private static boolean hasChatSignal(String text) {
        return hasAny(text, "단톡", "단체 채팅", "채팅방", "카톡", "메시지", "dm", "디엠");
    }

    private static boolean hasPostSignal(String text) {
        return hasAny(text, "sns", "인스타", "게시", "댓글", "사진 올", "사진이 올라", "사진 올라",
                "영상", "유포", "공유", "퍼졌", "온라인");
    }

    private static boolean hasSocialExclusionSignal(String text) {
        return hasAny(text, "따돌", "왕따", "무시", "배제", "끼워주지", "소외", "혼자");
    }

    private static boolean hasExtortionSignal(String text) {
        return hasAny(text, "돈", "갈취", "빼앗", "내놔", "물건", "강요", "심부름");
    }

    private static boolean hasStalkingSignal(String text) {
        return hasAny(text, "스토킹", "따라", "기다리", "집 앞", "계속 연락", "찾아와");
    }

    private static boolean hasThreatOrStalkingEvidenceContext(String text) {
        return hasAny(text,
                "칼", "흉기", "죽이", "죽여", "살해", "협박", "위협", "해치",
                "집 앞", "찾아와", "기다리", "쫓아", "따라", "스토킹", "보복");
    }

    private static boolean hasDemeaningSpeechSignal(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        boolean directPhrase = containsAny(t,
                "엄마 없", "엄마가 없", "아빠 없", "아빠가 없", "부모 없", "부모님 없",
                "고아", "패드립", "부모 욕", "가족 욕", "엄마 욕", "아빠 욕", "가정사 조롱",
                "외모 비하", "몸매 비하", "얼굴 비하", "못생", "돼지", "뚱뚱", "키 작", "키가 작",
                "장애 비하", "장애인 비하", "특수학급 비하",
                "성적 모욕", "성적인 욕", "성희롱성 욕", "성희롱 욕",
                "사생활 조롱", "비밀을 퍼뜨", "소문을 퍼뜨", "소문 유포",
                "인격 모독", "인신공격");
        boolean categoryMockery = containsAny(t, "가족", "엄마", "아빠", "부모", "가정사", "외모", "몸매", "얼굴", "장애", "특수학급", "사생활", "비밀")
                && containsAny(t, "비하", "조롱", "놀림", "놀렸", "모욕", "욕", "드립", "없대", "없다고", "퍼뜨", "소문");
        boolean groupChatMockery = containsAny(t, "단톡", "단체 채팅", "채팅방", "카톡")
                && containsAny(t, "비하", "조롱", "놀림", "모욕", "소문", "퍼뜨");
        return directPhrase || categoryMockery || groupChatMockery;
    }

    private static ConfirmationCandidate incidentQuestion(String text) {
        if (hasSexualViolationSignal(text)) {
            return candidate("incident_sexual", "성적으로 불쾌했던 일이 어떤 방식이었나요? 원하지 않은 신체 접촉, 성적인 말, 사진·촬영·유포 중 가까운 것을 골라주세요.",
                    option("원치 않는 접촉", "확인 답변: 원하지 않는 신체 접촉이 있었습니다."),
                    option("성적인 말", "확인 답변: 성적인 말이나 농담 때문에 불쾌했습니다."),
                    option("사진·촬영·유포", "확인 답변: 사진이나 촬영, 유포와 관련된 일이 있었습니다."),
                    option("반복 접근", "확인 답변: 원하지 않는데 반복적으로 다가오거나 접촉했습니다."),
                    option("직접 입력", "확인 답변: "));
        }
        if (hasAny(text, "sns", "인스타", "게시", "댓글", "사진", "영상")) {
            return candidate("incident_post", "온라인에 올라온 내용이 정확히 무엇인가요? 사진, 글, 댓글, 공유 중 해당하는 것을 골라주세요.",
                    option("사진·영상", "확인 답변: 사진이나 영상이 올라왔습니다."),
                    option("비방 글", "확인 답변: 비방 글이 올라왔습니다."),
                    option("댓글 조롱", "확인 답변: 댓글로 조롱이나 비방이 있었습니다."),
                    option("공유·유포", "확인 답변: 다른 사람에게 공유되거나 퍼졌습니다."),
                    option("직접 입력", "확인 답변: "));
        }
        if (hasPhysicalViolenceSignal(text)) {
            return candidate("incident_physical", "몸에 어떤 일이 있었나요? 맞음, 밀침, 넘어짐, 상처 중 가까운 것을 골라주세요.",
                    option("맞음", "확인 답변: 맞거나 가격당한 일이 있었습니다."),
                    option("밀침", "확인 답변: 밀치거나 넘어뜨리는 행동이 있었습니다."),
                    option("상처·멍", "확인 답변: 멍이나 상처가 생겼습니다."),
                    option("위협 동반", "확인 답변: 신체 행동과 함께 위협이 있었습니다."),
                    option("직접 입력", "확인 답변: "));
        }
        return candidate("incident", "친구가 실제로 어떤 행동을 했는지 사실만 한 문장으로 적어주세요. 학교폭력 유형은 제가 판단하겠습니다.",
                option("욕하거나 놀림", "확인 답변: 친구가 욕하거나 놀렸습니다."),
                option("사진·글을 올림", "확인 답변: 친구가 사진이나 글을 올렸습니다."),
                option("때리거나 밀침", "확인 답변: 친구가 때리거나 밀쳤습니다."),
                option("따돌리거나 제외", "확인 답변: 친구가 따돌리거나 같이하지 못하게 했습니다."),
                option("직접 입력", "확인 답변: "));
    }

    private static ConfirmationCandidate relationshipQuestion(String text) {
        return candidate("relationship", "상대와의 관계를 학교폭력 절차 기준으로 확인해야 합니다. 같은 반, 같은 학교, 선후배·학원, 학교 밖 중 어디에 가깝나요?",
                option("같은 반", "확인 답변: 상대는 같은 반 학생입니다."),
                option("같은 학교", "확인 답변: 상대는 같은 학교 학생입니다."),
                option("선후배·학원", "확인 답변: 상대는 선후배 또는 학원 관계입니다."),
                option("학교 밖", "확인 답변: 상대는 학교 관계자가 아닙니다."),
                option("아직 모름", "확인 답변: 상대가 학교 관계자인지는 아직 모르겠습니다."));
    }

    private static ConfirmationCandidate timelineQuestion(String text) {
        if (hasAny(text, "단톡", "단체 채팅", "채팅방", "카톡", "메시지", "sns", "게시", "댓글")) {
            return candidate("timeline_cyber", "온라인 괴롭힘이 언제부터 어떤 빈도로 이어졌나요? 한 번인지, 며칠 이상 반복인지, 지금도 이어지는지 알려주세요.",
                    option("한 번", "확인 답변: 현재까지는 한 번 발생했습니다."),
                    option("며칠 반복", "확인 답변: 며칠 동안 반복됐습니다."),
                    option("오래 반복", "확인 답변: 몇 주 이상 반복됐습니다."),
                    option("지금도 계속", "확인 답변: 지금도 계속되고 있습니다."),
                    option("직접 입력", "확인 답변: "));
        }
        return candidate("timeline", "언제부터 몇 번 있었고, 지금도 계속되고 있나요?",
                option("오늘 한 번", "확인 답변: 오늘 현재까지는 한 번 발생했습니다."),
                option("최근 여러 번", "확인 답변: 최근 비슷한 일이 여러 번 반복됐습니다."),
                option("오래 지속", "확인 답변: 몇 주 이상 지속됐습니다."),
                option("지금도 계속", "확인 답변: 지금도 계속되고 있습니다."),
                option("기억 안 남", "확인 답변: 정확한 시점이나 횟수는 아직 기억나지 않습니다."));
    }

    private static ConfirmationCandidate groupChatTimelineQuestion() {
        return candidate("chat_timeline", "이 단체 채팅방에서 이런 일이 얼마나 자주, 언제부터 일어났나요?",
                option("오늘 처음", "확인 답변: 오늘 처음 일어난 일입니다."),
                option("며칠 전부터", "확인 답변: 며칠 전부터 가끔씩 있었습니다."),
                option("오래 지속", "확인 답변: 꽤 오래전부터 지속적으로 일어났습니다."),
                option("지금도 계속", "확인 답변: 지금도 계속되고 있습니다."),
                option("직접 입력", "확인 답변: "));
    }

    private static ConfirmationCandidate groupChatScaleQuestion() {
        return candidate("chat_scale", "그 말을 보거나 대화방에 함께 있던 사람이 몇 명 정도였나요?",
                option("소수만 봄", "확인 답변: 단체 채팅방에서 소수만 그 말을 봤습니다."),
                option("여러 명이 봄", "확인 답변: 단체 채팅방에서 여러 명이 그 말을 봤습니다."),
                option("반 전체 수준", "확인 답변: 반 전체에 가까운 인원이 볼 수 있는 대화방이었습니다."),
                option("잘 모르겠음", "확인 답변: 대화방 인원이나 본 사람 수는 아직 잘 모르겠습니다."),
                option("직접 입력", "확인 답변: "));
    }

    private static ConfirmationCandidate evidenceQuestion(String text) {
        if (hasSexualViolationSignal(text)) {
            return candidate("evidence_sexual", "지금 남길 수 있는 단서는 무엇인가요? 당시 장소·시간, 주변에 있던 사람, 이후 메시지, 상담 기록 중 가까운 것을 골라주세요.",
                    option("장소·시간", "확인 답변: 당시 장소와 대략적인 시간을 기억하고 있습니다."),
                    option("주변 사람", "확인 답변: 주변에 있던 사람이 있었습니다."),
                    option("메시지·연락", "확인 답변: 이후 메시지나 연락 기록이 있습니다."),
                    option("아직 없음", "확인 답변: 아직 확보한 증거는 없습니다."),
                    option("직접 입력", "확인 답변: "));
        }
        if (hasAny(text, "단톡", "단체 채팅", "채팅방", "카톡", "메시지")) {
            return candidate("evidence_chat", "대화 증거에는 무엇이 남아 있나요? 리포트에는 참여자, 시간, 앞뒤 맥락이 중요합니다.",
                    option("참여자+시간", "확인 답변: 참여자 목록과 보낸 시간이 보이는 캡처가 있습니다."),
                    option("앞뒤 맥락", "확인 답변: 앞뒤 대화 맥락이 보이는 캡처가 있습니다."),
                    option("일부 캡처만", "확인 답변: 일부 캡처만 있고 전체 맥락은 부족합니다."),
                    option("아직 없음", "확인 답변: 아직 확보한 증거는 없습니다."),
                    option("직접 입력", "확인 답변: "));
        }
        if (hasThreatOrStalkingEvidenceContext(text)) {
            return candidate("evidence_threat", "협박이나 반복 방문을 확인할 수 있는 단서는 무엇이 있나요? 녹음, CCTV, 방문 기록, 목격자 중 가까운 것을 골라주세요.",
                    option("녹음·통화", "확인 답변: 협박 발언이 담긴 녹음이나 통화 기록이 있습니다."),
                    option("CCTV·방문 기록", "확인 답변: 집 앞 방문 장면을 확인할 수 있는 CCTV나 경비 기록이 있습니다."),
                    option("메시지·연락", "확인 답변: 협박이나 반복 연락이 남아 있는 메시지 기록이 있습니다."),
                    option("목격자", "확인 답변: 상황을 본 목격자가 있습니다."),
                    option("아직 없음", "확인 답변: 아직 확보한 증거는 없습니다."),
                    option("직접 입력", "확인 답변: "));
        }
        if (hasPhysicalViolenceSignal(text) || hasAny(text, "병원", "진단")) {
            return candidate("evidence_physical", "신체 피해를 확인할 자료가 무엇인가요? 사진, 진료 기록, 목격자 중 해당하는 것을 골라주세요.",
                    option("상처 사진", "확인 답변: 멍이나 상처 사진이 있습니다."),
                    option("진료 기록", "확인 답변: 병원 진단서나 진료 기록이 있습니다."),
                    option("목격자", "확인 답변: 상황을 본 목격자가 있습니다."),
                    option("아직 없음", "확인 답변: 아직 확보한 증거는 없습니다."),
                    option("직접 입력", "확인 답변: "));
        }
        if (isPerpetratorText(text)) {
            return candidate("evidence_actor", "본인이 한 행동을 확인할 자료가 있나요? 원본, 삭제 기록, 사과·상담 기록 중 무엇이 있나요?",
                    option("대화·게시 원본", "확인 답변: 대화나 게시물 원본이 있습니다."),
                    option("삭제 기록", "확인 답변: 삭제나 수정한 내역이 있습니다."),
                    option("상담 기록", "확인 답변: 보호자나 담임에게 말한 기록이 있습니다."),
                    option("아직 없음", "확인 답변: 아직 정리한 자료는 없습니다."),
                    option("직접 입력", "확인 답변: "));
        }
        return candidate("evidence", "지금 남아 있는 증거는 무엇인가요? 캡처, URL, 사진, 진단서, 목격자 중 골라주세요.",
                option("캡처·URL", "확인 답변: 캡처나 URL을 가지고 있습니다."),
                option("사진·진단서", "확인 답변: 사진, 진단서, 병원 기록 중 일부가 있습니다."),
                option("목격자", "확인 답변: 상황을 본 목격자가 있습니다."),
                option("아직 없음", "확인 답변: 아직 확보한 증거는 없습니다."),
                option("직접 입력", "확인 답변: "));
    }

    private static ConfirmationCandidate impactQuestion(String text) {
        if (isPerpetratorText(text)) {
            return candidate("impact_actor", "지금 피해 회복을 위해 이미 한 일이 있나요? 중단, 삭제, 보호자·담임 공유, 사과 시도 중 골라주세요.",
                    option("중단함", "확인 답변: 문제 행동을 중단했습니다."),
                    option("삭제함", "확인 답변: 게시물이나 댓글을 삭제했습니다."),
                    option("어른에게 알림", "확인 답변: 보호자나 담임에게 알렸습니다."),
                    option("아직 못 함", "확인 답변: 아직 피해 회복 조치를 하지 못했습니다."),
                    option("직접 입력", "확인 답변: "));
        }
        return candidate("impact", "이 일 때문에 지금 가장 크게 영향을 받은 부분은 무엇인가요?",
                option("불안·두려움", "확인 답변: 불안하거나 두려운 영향이 있습니다."),
                option("등교 어려움", "확인 답변: 학교에 가기 어렵거나 생활에 영향이 있습니다."),
                option("보복 걱정", "확인 답변: 보복이나 반복이 걱정됩니다."),
                option("큰 영향 없음", "확인 답변: 현재 크게 불편한 점이나 생활 영향은 없습니다."),
                option("이미 알림", "확인 답변: 보호자나 담임에게 일부 알렸습니다."),
                option("직접 입력", "확인 답변: "));
    }

    private static ConfirmationCandidate goalQuestion(String text) {
        if (isPerpetratorText(text)) {
            return candidate("goal_actor", "가장 필요한 도움은 무엇인가요? 사과 방식, 삭제·중단, 학교 절차, 재발 방지 중 골라주세요.",
                    option("사과 방식", "확인 답변: 사과나 피해 회복 방법을 알고 싶습니다."),
                    option("삭제·중단", "확인 답변: 게시물 삭제와 추가 행동 중단 방법이 필요합니다."),
                    option("학교 절차", "확인 답변: 학교에 어떻게 설명할지 알고 싶습니다."),
                    option("재발 방지", "확인 답변: 다시 하지 않기 위한 계획이 필요합니다."),
                    option("직접 입력", "확인 답변: "));
        }
        return candidate("goal", "이 상담에서 가장 필요한 도움은 무엇인가요?",
                option("증거 정리", "확인 답변: 증거를 어떻게 정리할지 알고 싶습니다."),
                option("신고·상담", "확인 답변: 신고나 학교 상담 절차를 알고 싶습니다."),
                option("안전 확보", "확인 답변: 보복이나 반복을 막고 안전하게 보호받고 싶습니다."),
                option("관계 정리", "확인 답변: 상대와 어떻게 거리를 둬야 할지 알고 싶습니다."),
                option("직접 입력", "확인 답변: "));
    }

    private static ConfirmationCandidate finalCheckQuestion() {
        return candidate("final_check", "리포트를 생성하기 전에 확인할게요. 같은 가해자에게 당한 다른 피해가 있으면 작성해 주세요. 없다면 지금까지 말한 내용으로 리포트를 생성해도 됩니다.",
                option("이 내용으로 생성", "확인 답변: 위 내용은 하나의 같은 사안이며 이 내용으로 리포트를 생성해도 됩니다."),
                option("다른 피해 추가", "확인 답변: 같은 가해자에게 당한 다른 피해가 더 있습니다. "),
                option("피해 정도 추가", "확인 답변: 피해 정도나 당시 상황을 더 설명하겠습니다. "),
                option("직접 입력", "확인 답변: "));
    }

    private static ConfirmationCandidate realityCheckQuestion() {
        return candidate("reality_check", "말해준 내용이 실제로 있었던 일인지, 비유나 장난 표현인지 먼저 확인할게요.",
                option("실제 사건", "확인 답변: 실제로 있었던 일입니다."),
                option("비유·농담", "확인 답변: 비유나 농담 표현입니다."),
                option("설명 보완", "확인 답변: "));
    }

    private static ConfirmationCandidate roleConflictQuestion() {
        return candidate("role_conflict", "앞에서는 피해를 당한 내용이 있었고, 방금은 본인이 한 행동도 나온 것 같아요. 같은 사건 안에서 서로 충돌한 내용인가요, 아니면 별도 사건인가요?",
                option("같은 사건 안의 충돌", "확인 답변: 같은 사안에서 서로 충돌한 내용입니다."),
                option("별도 사건", "확인 답변: 방금 말한 내용은 별도 사안입니다. 새 상담으로 분리하는 게 좋습니다."),
                option("나는 피해 입장", "확인 답변: 저는 피해를 당한 입장이고 가해한 내용은 아닙니다."),
                option("내 행동도 있음", "확인 답변: 제가 한 행동도 있는 사안입니다."));
    }

    private static List<ConfirmationCandidate> deepDiveQuestions(String text, long userMessageCount) {
        List<ConfirmationCandidate> questions = new ArrayList<>();
        boolean actor = isPerpetratorText(text);
        boolean groupChat = hasAny(text, "단톡", "단체 채팅", "채팅방");
        boolean post = hasAny(text, "sns", "인스타", "게시", "댓글", "유포", "온라인")
                || (hasAny(text, "사진", "영상") && hasAny(text, "올렸", "올라", "퍼졌", "공유", "단톡", "채팅방"));
        boolean sexual = hasSexualViolationSignal(text);
        boolean threat = hasThreatOrStalkingEvidenceContext(text);
        boolean physical = hasPhysicalViolenceSignal(text) || hasAny(text, "병원", "진단");

        if (actor) {
            if (!hasAny(text, "중단", "그만", "삭제", "수정", "남아", "남아 있", "확인하지 못")) {
                questions.add(candidate("actor_stop", "지금 문제 행동은 완전히 중단됐나요? 게시물·댓글·대화가 남아 있다면 어떻게 처리했는지도 알려주세요.",
                        option("완전히 중단", "확인 답변: 문제 행동은 완전히 중단했습니다."),
                        option("삭제함", "확인 답변: 남아 있던 게시물이나 댓글을 삭제했습니다."),
                        option("아직 남아 있음", "확인 답변: 아직 남아 있는 게시물이나 대화가 있습니다."),
                        option("모르겠음", "확인 답변: 남아 있는지 아직 확인하지 못했습니다."),
                        option("직접 입력", "확인 답변: ")));
            }
            if (!hasAny(text, "사과", "피해 회복", "보호자", "담임", "상담")) {
                questions.add(candidate("actor_recovery", "피해 회복은 어떤 방식으로 생각하고 있나요? 직접 연락보다 보호자나 학교를 통한 방식이 안전합니다.",
                        option("학교 통해 사과", "확인 답변: 학교나 담임을 통해 사과하고 싶습니다."),
                        option("보호자와 상의", "확인 답변: 보호자와 먼저 상의하겠습니다."),
                        option("회복 방법 모름", "확인 답변: 어떤 방식으로 피해 회복을 해야 할지 모르겠습니다."),
                        option("이미 상담함", "확인 답변: 보호자나 담임에게 이미 상담했습니다."),
                        option("직접 입력", "확인 답변: ")));
            }
            return questions.isEmpty() ? List.of(genericDetailQuestion(text)) : questions;
        }

        if (sexual) {
            if (!hasAny(text, "담임", "선생", "보호자", "부모", "117", "상담", "말했", "알렸")) {
                questions.add(candidate("sexual_support", "이 일을 안전하게 말할 수 있는 어른이나 상담 창구가 있나요? 보호자, 상담교사, 담임, 117 중 편한 쪽부터 생각해도 됩니다.",
                        option("보호자", "확인 답변: 보호자에게 말할 수 있습니다."),
                        option("상담교사·담임", "확인 답변: 상담교사나 담임에게 말할 수 있습니다."),
                        option("117", "확인 답변: 117 상담을 먼저 이용하고 싶습니다."),
                        option("아직 어려움", "확인 답변: 아직 누구에게 말해야 할지 어렵습니다."),
                        option("직접 입력", "확인 답변: ")));
            }
            if (!hasAny(text, "장소", "시간", "목격", "주변", "기억", "메시지", "연락")) {
                questions.add(candidate("sexual_context", "기억나는 범위에서 장소, 시간대, 주변에 있던 사람 중 말할 수 있는 게 있나요?",
                        option("장소 기억", "확인 답변: 장소를 기억하고 있습니다."),
                        option("시간대 기억", "확인 답변: 대략적인 시간대를 기억하고 있습니다."),
                        option("주변 사람", "확인 답변: 주변에 있던 사람이 있었습니다."),
                        option("아직 정리 어려움", "확인 답변: 아직 자세히 정리하기 어렵습니다."),
                        option("직접 입력", "확인 답변: ")));
            }
            return questions.isEmpty() ? List.of(genericDetailQuestion(text)) : questions;
        }

        if (threat) {
            if (!hasAny(text, "보호자", "부모", "담임", "선생", "학교", "경찰", "112", "신고", "알렸", "대응 중", "도움")) {
                questions.add(candidate("threat_safety", "지금 이 위협을 함께 알고 대응해줄 어른이나 기관이 있나요? 보호자, 학교, 경찰 신고 여부가 가장 중요합니다.",
                        option("보호자에게 알림", "확인 답변: 부모님이나 보호자에게 이미 이 사실을 알렸고 함께 대응 중입니다."),
                        option("학교에 알림", "확인 답변: 학교 선생님께 이 사실을 알렸고 학교 차원의 조치를 요청했습니다."),
                        option("경찰 신고", "확인 답변: 경찰 신고를 진행했거나 진행할 예정입니다."),
                        option("아직 혼자임", "확인 답변: 아직 보호자나 학교, 경찰에는 알리지 못했습니다."),
                        option("직접 입력", "확인 답변: ")));
            }
            if (!hasAny(text, "녹음", "통화", "CCTV", "cctv", "메시지", "목격", "경비", "방문 기록", "신고 접수", "접수 기록")) {
                questions.add(evidenceQuestion(text));
            }
            if (!hasAny(text, "분리", "보호 조치", "접근 금지", "등하교", "동선", "같은 반 분리", "학교 조치")) {
                questions.add(candidate("threat_school_action", "학교 안팎에서 마주칠 가능성이 있나요? 필요한 보호 조치를 리포트에 정확히 넣기 위해 확인할게요.",
                        option("같은 반 분리", "확인 답변: 가해 학생과 같은 반이라 즉시 분리 조치가 필요합니다."),
                        option("등하교 보호", "확인 답변: 등하교 동선 보호나 동행 조치가 필요합니다."),
                        option("접근 금지 요청", "확인 답변: 학교와 경찰에 접근 금지나 접촉 제한을 요청하고 싶습니다."),
                        option("상담만 진행", "확인 답변: 학교 선생님과 상담만 진행되었고 구체적인 보호 조치는 아직 없습니다."),
                        option("직접 입력", "확인 답변: ")));
            }
            return questions.isEmpty() ? List.of(goalQuestion(text)) : questions;
        }

        if (groupChat) {
            if (!hasGroupChatTimelineDetailAnswer(text)) {
                questions.add(groupChatTimelineQuestion());
            }
            if (!hasGroupChatScaleAnswer(text)) {
                questions.add(groupChatScaleQuestion());
            }
            if (!hasAny(text, "주도", "여러 명이 같이", "여러 명이 함께", "한 명이 주도", "강퇴", "초대 제외", "읽씹", "단체로 무시")) {
                questions.add(candidate("chat_pattern", "단톡방에서는 괴롭힘이 어떤 방식으로 반복됐나요? 여러 명이 같이 한 건지, 한 명이 주도한 건지, 배제도 있었는지 확인해야 합니다.",
                        option("여러 명이 같이", "확인 답변: 여러 명이 함께 조롱하거나 비방했습니다."),
                        option("한 명이 주도", "확인 답변: 한 명이 주도하고 다른 친구들이 반응했습니다."),
                        option("초대 제외·강퇴", "확인 답변: 초대 제외나 강퇴 같은 배제가 있었습니다."),
                        option("읽씹·무시", "확인 답변: 단체로 무시하거나 읽씹하는 일이 있었습니다."),
                        option("직접 입력", "확인 답변: ")));
            }
            if (userMessageCount >= 4 && !hasAny(text, "담임", "선생", "보호자", "부모", "117", "신고")) {
                questions.add(candidate("chat_support", "이 대화방 일을 지금 알고 있는 어른이나 학교 담당자가 있나요?",
                        option("보호자에게 알림", "확인 답변: 보호자에게 알렸습니다."),
                        option("담임에게 알림", "확인 답변: 담임이나 학교에 알렸습니다."),
                        option("아직 말 못 함", "확인 답변: 아직 어른이나 학교에 말하지 못했습니다."),
                        option("말하기 두려움", "확인 답변: 말하면 보복당할까 봐 두렵습니다."),
                        option("직접 입력", "확인 답변: ")));
            }
        }

        if (post) {
            if (!hasAny(text, "공개", "비공개", "팔로워", "공유", "퍼졌", "유포", "댓글")) {
                questions.add(candidate("post_spread", "게시물이나 사진이 어느 정도 퍼졌나요? 공개 범위와 댓글·공유 여부가 중요합니다.",
                        option("공개 게시물", "확인 답변: 공개 게시물로 올라왔습니다."),
                        option("친구들만", "확인 답변: 친구들이 볼 수 있는 범위로 올라왔습니다."),
                        option("댓글 있음", "확인 답변: 댓글이나 추가 조롱이 붙었습니다."),
                        option("공유됨", "확인 답변: 다른 사람에게 공유되거나 퍼졌습니다."),
                        option("직접 입력", "확인 답변: ")));
            }
            if (!hasAny(text, "URL", "링크", "작성자", "게시 시간", "게시글 번호", "계정")
                    && !hasUnknownActorSignal(text)) {
                questions.add(candidate("post_trace", "게시물의 URL, 작성자 계정, 게시 시간 중 확인 가능한 게 있나요?",
                        option("URL 있음", "확인 답변: 게시물 URL이나 링크가 있습니다."),
                        option("작성자 계정", "확인 답변: 작성자 계정 정보를 알고 있습니다."),
                        option("게시 시간", "확인 답변: 게시 시간이 보이는 자료가 있습니다."),
                        option("캡처만 있음", "확인 답변: 현재는 캡처만 있습니다."),
                        option("직접 입력", "확인 답변: ")));
            }
        }

        if (physical) {
            if (!hasAny(text, "부위", "팔", "다리", "얼굴", "배", "머리", "통증")) {
                questions.add(candidate("physical_injury", "멍이나 통증이 있다면 어느 부위이고 지금 상태는 어떤가요?",
                        option("팔·다리", "확인 답변: 팔이나 다리에 멍 또는 통증이 있습니다."),
                        option("얼굴·머리", "확인 답변: 얼굴이나 머리 쪽 피해가 있습니다."),
                        option("몸통", "확인 답변: 배나 몸통 쪽 피해가 있습니다."),
                        option("통증 지속", "확인 답변: 시간이 지나도 통증이 계속됩니다."),
                        option("직접 입력", "확인 답변: ")));
            }
            if (!hasAny(text, "병원", "진단", "치료", "보건실", "목격")) {
                questions.add(candidate("physical_support", "신체 피해를 본 사람이나 진료·보건실 기록이 있나요?",
                        option("목격자 있음", "확인 답변: 상황을 본 목격자가 있습니다."),
                        option("보건실 기록", "확인 답변: 보건실이나 학교에 기록이 있습니다."),
                        option("병원 예정", "확인 답변: 병원 진료를 받을 예정입니다."),
                        option("아직 없음", "확인 답변: 아직 진료나 목격자 확인은 없습니다."),
                        option("직접 입력", "확인 답변: ")));
            }
            if (!hasAny(text, "피해 정도", "가볍게", "크게 다치", "친구들이 알고", "여러 명이 보", "상대가 흥분", "상대가 분노", "당시 상황")) {
                questions.add(candidate("physical_context", "피해 정도와 당시 상황도 위험도 판단에 중요합니다. 맞거나 밀친 정도, 친구들이 알고 있는지, 상대가 흥분한 상태였는지 중 가까운 내용을 골라주세요.",
                        option("가벼운 1회", "확인 답변: 한 번 가볍게 밀치거나 맞았고 크게 다치지는 않았습니다."),
                        option("멍·통증 있음", "확인 답변: 멍이나 통증이 있습니다."),
                        option("반복됨", "확인 답변: 비슷한 신체 피해가 여러 번 반복됐습니다."),
                        option("친구들이 앎", "확인 답변: 여러 명이 보거나 친구들이 알고 있습니다."),
                        option("상대가 흥분", "확인 답변: 당시 상대가 흥분하거나 분노한 상태였습니다."),
                        option("보복 우려", "확인 답변: 보복이나 다시 맞을까 봐 걱정됩니다."),
                        option("직접 입력", "확인 답변: ")));
            }
        }

        if (questions.isEmpty()) {
            questions.add(genericDetailQuestion(text));
        }
        return questions;
    }

    private static ConfirmationCandidate genericDetailQuestion(String text) {
        return candidate("more_context", "지금 가장 걱정되는 부분은 무엇인가요?",
                option("보복", "확인 답변: 보복이나 반복이 가장 걱정됩니다."),
                option("증거 부족", "확인 답변: 증거가 충분한지 걱정됩니다."),
                option("학교에 말하기", "확인 답변: 학교나 담임에게 말하는 것이 걱정됩니다."),
                option("관계 악화", "확인 답변: 친구 관계가 더 나빠질까 봐 걱정됩니다."),
                option("직접 입력", "확인 답변: "));
    }

    private static ConfirmationCandidate candidate(String id, String question, Map<String, String>... options) {
        return new ConfirmationCandidate(id, question, List.of(options));
    }

    private static Map<String, String> option(String label, String message) {
        return Map.of("label", label, "message", message);
    }

    public List<Map<String, Object>> getSessions(User user) {
        List<Session> sessions = sessionRepository.findTop80ByUserOrderByCreatedAtDesc(user);
        List<Long> sessionIds = sessions.stream()
                .map(Session::getId)
                .toList();
        if (sessionIds.isEmpty()) return List.of();

        Map<Long, Long> messageCounts = messageRepository.countUserMessagesBySessionIds(sessionIds).stream()
                .collect(Collectors.toMap(
                        MessageRepository.SessionMessageCount::getSessionId,
                        MessageRepository.SessionMessageCount::getMessageCount
                ));
        Map<Long, List<Message>> userMessagesBySession = messageRepository.findUserMessagesBySessionIds(sessionIds).stream()
                .collect(Collectors.groupingBy(message -> message.getSession().getId(), LinkedHashMap::new, Collectors.toList()));

        return sessions.stream()
                .map(session -> {
                    return Map.<String, Object>of(
                            "session_id", session.getId(),
                            "preview", summarizeSessionPreview(userMessagesBySession.getOrDefault(session.getId(), List.of())),
                            "message_count", messageCounts.getOrDefault(session.getId(), 0L),
                            "created_at", session.getCreatedAt().toString()
                    );
                })
                .toList();
    }

    static String summarizeSessionPreview(List<Message> userMessages) {
        if (userMessages == null || userMessages.isEmpty()) return "새 상담";
        List<String> contents = userMessages.stream()
                .filter(Objects::nonNull)
                .map(Message::getContent)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(content -> !content.isBlank())
                .toList();
        if (contents.isEmpty()) return "새 상담";

        String combined = String.join(" ", contents).toLowerCase(Locale.ROOT);
        if (containsAny(combined, "sns", "인스타", "게시", "온라인", "댓글", "사진", "영상")
                && containsAny(combined, "비방", "욕", "모욕", "조롱", "소문")) {
            if (containsAny(combined, "사진", "영상")) return "SNS 사진 비방 대응";
            return "SNS 비방 글 대응";
        }
        if (containsAny(combined, "단톡", "단체 채팅", "채팅방", "카톡", "메시지")
                && containsAny(combined, "욕", "비방", "조롱", "놀림", "소문", "무시", "읽씹")) {
            return "단체 채팅방 괴롭힘 상담";
        }
        if (hasSexualViolationSignal(combined)) return "성적 괴롭힘 상담";
        if (hasPhysicalViolenceSignal(combined) || hasBodilyWasteIncidentSignal(combined)) return "신체 폭력 피해 상담";
        if (hasSocialExclusionSignal(combined)) return "따돌림 피해 상담";
        if (hasExtortionSignal(combined)) return "금품 갈취 상담";
        if (hasStalkingSignal(combined)) return "스토킹 피해 상담";
        if (containsAny(combined, "욕", "비방", "모욕", "조롱", "놀림", "소문", "허위")) {
            return "욕설·비방 피해 상담";
        }
        if (containsAny(combined, "수업", "성적", "등교", "잠", "수면", "불안", "두려움")) {
            return "피해 영향 상담";
        }

        String seed = contents.stream()
                .filter(content -> !isGreetingOnly(content))
                .filter(content -> !isConfirmationAnswer(content))
                .findFirst()
                .orElse(contents.get(0));
        return compactSessionPreview(seed);
    }

    private static String compactSessionPreview(String content) {
        String preview = content == null ? "" : content.trim();
        preview = preview.replaceFirst("^(확인 답변|추가 설명|답변)\\s*:\\s*", "");
        preview = preview.replaceAll("[\\r\\n]+", " ");
        preview = preview.replaceAll("\\s+", " ");
        preview = preview.replaceAll("(입니다|습니다|어요|예요|네요|요)[.!?。]*$", "");
        preview = preview.replaceAll("[.!?。]+$", "");
        if (preview.isBlank()) return "새 상담";
        if (preview.length() > 22) return preview.substring(0, 22).trim() + "...";
        return preview;
    }

    private List<Message> guestHistory(List<HistoryMessage> clientHistory, String latestUserMessage) {
        List<Message> items = new ArrayList<>();
        if (clientHistory != null) {
            for (HistoryMessage item : clientHistory) {
                if (item == null) continue;
                String role = normalizeGuestRole(item.role());
                String content = item.content() == null ? "" : item.content().trim();
                if (role == null || content.isBlank()) continue;
                items.add(transientMessage(role, trimForGuestHistory(content)));
            }
        }

        int from = Math.max(0, items.size() - GUEST_HISTORY_LIMIT);
        List<Message> recent = new ArrayList<>(items.subList(from, items.size()));
        recent.add(transientMessage("user", latestUserMessage));
        return recent;
    }

    private String normalizeGuestRole(String role) {
        if (role == null) return null;
        return switch (role.trim().toLowerCase(Locale.ROOT)) {
            case "user" -> "user";
            case "assistant", "ai" -> "assistant";
            default -> null;
        };
    }

    private String trimForGuestHistory(String content) {
        return content.length() > 2000 ? content.substring(0, 2000) : content;
    }

    private Message transientMessage(String role, String content) {
        Message message = new Message();
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    private Session resolveSession(User user, Long sessionId) {
        if (sessionId == null) return createSession(user);
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "상담 세션을 찾을 수 없습니다."));
        requireOwner(user, session);
        return session;
    }

    private void requireOwner(User user, Session session) {
        if (session.getUser() == null || !session.getUser().getId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "상담 세션 접근 권한이 없습니다.");
        }
    }

    private GeneratedReply getAiReply(List<Message> history) {
        String latestUserMessage = latestUserMessage(history);
        String userContextText = combinedUserText(history);
        if (isGreetingOnly(latestUserMessage)) {
            lastProvider = "local_greeting";
            return new GeneratedReply(buildGreetingReply(), null);
        }
        if (shouldGuardIrrelevantInput(latestUserMessage, hasConversationContext(history), isConversationalFollowUp(latestUserMessage, history))) {
            lastProvider = "guardrail";
            if (isPerpetratorContext(userContextText)) {
                return new GeneratedReply(buildPerpetratorOffTopicReply(), null);
            }
            return new GeneratedReply(buildIrrelevantInputReply(), null);
        }

        PromptContext promptContext = buildPromptContext(history);
        Exception lastError = null;

        if (!effectiveDeepSeekApiKey().isBlank() && System.currentTimeMillis() > deepSeekDisabledUntil) {
            try {
                GeneratedReply reply = requireValidGeneratedReply(
                        callDeepSeekApi(history, promptContext.prompt()),
                        promptContext
                );
                lastProvider = "deepseek";
                return adaptSensitiveReply(reply, latestUserMessage, userContextText, promptContext);
            } catch (Exception e) {
                lastError = e;
                deepSeekDisabledUntil = System.currentTimeMillis() + cooldownFor(e);
                logProviderFailure("DeepSeek", e);
            }
        }

        if (geminiApiKey != null && !geminiApiKey.isBlank()
                && System.currentTimeMillis() > geminiDisabledUntil) {
            try {
                GeneratedReply reply = requireValidGeneratedReply(
                        callGeminiApi(history, promptContext.prompt()),
                        promptContext
                );
                lastProvider = "gemini";
                return adaptSensitiveReply(reply, latestUserMessage, userContextText, promptContext);
            } catch (Exception e) {
                lastError = e;
                geminiDisabledUntil = System.currentTimeMillis() + cooldownFor(e);
                logProviderFailure("Gemini", e);
            }
        }

        if (groqApiKey != null && !groqApiKey.isBlank() && System.currentTimeMillis() > groqDisabledUntil) {
            try {
                GeneratedReply reply = requireValidGeneratedReply(
                        callGroqApi(history, promptContext.prompt()),
                        promptContext
                );
                lastProvider = "groq";
                return adaptSensitiveReply(reply, latestUserMessage, userContextText, promptContext);
            } catch (Exception e) {
                lastError = e;
                groqDisabledUntil = System.currentTimeMillis() + cooldownFor(e);
                logProviderFailure("Groq", e);
            }
        }

        if (isClaudeBackupAvailable()) {
            try {
                GeneratedReply reply = requireValidGeneratedReply(
                        callClaudeApi(history, promptContext.prompt()),
                        promptContext
                );
                lastProvider = "claude";
                return adaptSensitiveReply(reply, latestUserMessage, userContextText, promptContext);
            } catch (Exception e) {
                lastError = e;
                logProviderFailure("Claude", e);
            }
        }

        String fallback = buildFallbackReply(history, promptContext);
        if (!fallback.isBlank()) {
            lastProvider = "law_fallback";
            System.err.println("[AI] 모든 외부 공급자 실패 후 법령 기반 fallback 응답 반환"
                    + (lastError == null ? "" : " (" + lastError.getClass().getSimpleName() + ")"));
            return adaptSensitiveReply(new GeneratedReply(fallback, null), latestUserMessage, userContextText, promptContext);
        }

        lastProvider = "unavailable";
        throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "AI 상담 서비스에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요."
        );
    }

    private GeneratedReply adaptSensitiveReply(GeneratedReply generatedReply, String latestUserMessage, String userContextText, PromptContext promptContext) {
        if (generatedReply == null) return new GeneratedReply("", null);
        String reply = generatedReply.reply();
        if (reply == null || promptContext == null) return generatedReply;
        String adapted = adaptCaseDomainWording(reply, userContextText);
        boolean conversationMode = promptContext.mode() == ReplyMode.CONVERSATION;
        boolean sexualContext = hasSexualViolationSignal(userContextText) || hasSexualViolationSignal(latestUserMessage);

        if (conversationMode
                && sexualContext
                && !containsAny(adapted, "성폭력", "성추행", "성희롱", "성적 괴롭힘", "원하지 않은 성적 접촉")) {
            adapted = "말씀하신 원하지 않은 접촉은 단순한 장난으로 넘길 일이 아니라 성폭력·성추행 맥락에서 도움을 요청할 수 있는 일입니다.\n"
                    + adapted;
        }
        if (asksMaleVictimCanConsult(latestUserMessage)
                && !containsAny(adapted, "남자", "성별", "당연히 상담", "상담해도 됩니다")) {
            adapted = "남자여도 당연히 상담해도 됩니다. 원하지 않은 성적 접촉이나 말은 성별과 관계없이 도움을 요청할 수 있는 일입니다.\n"
                    + adapted;
        }
        return new GeneratedReply(adapted, generatedReply.followUpQuestion());
    }

    static String adaptCaseDomainWording(String reply, String userContextText) {
        String adapted = adaptSexualViolenceWording(reply, userContextText);
        adapted = adaptVictimPhotoWording(adapted, userContextText);
        adapted = adaptGroupChatEvidenceWording(adapted, userContextText);
        return adapted;
    }

    static String adaptGroupChatEvidenceWording(String reply, String userContextText) {
        if (reply == null || reply.isBlank()) return reply;
        if (!hasGroupChatHarassmentSignal(userContextText)) return reply;
        String adapted = sanitizeGroupChatExitAdvice(reply);
        if (!hasGroupChatEvidencePreservationAdvice(adapted)) {
            return GROUP_CHAT_EVIDENCE_FIRST_ADVICE + "\n" + adapted;
        }
        return adapted;
    }

    static String adaptSexualViolenceWording(String reply, String userContextText) {
        if (reply == null || reply.isBlank()) return reply;
        if (!hasSexualViolationSignal(userContextText)) return reply;
        if (hasPhysicalViolenceSignal(userContextText) && !reply.contains("성폭력")) {
            return reply
                    .replace("신체폭력", "성폭력 또는 신체폭력")
                    .replace("신체 폭력", "성폭력 또는 신체 폭력");
        }

        return reply
                .replace("신체폭력", "성폭력")
                .replace("신체 폭력", "성폭력");
    }

    static String adaptVictimPhotoWording(String reply, String userContextText) {
        if (reply == null || reply.isBlank()) return reply;
        String t = userContextText == null ? "" : userContextText.toLowerCase(Locale.ROOT);
        if (!hasOwnPhotoPostedVictimContext(t) || hasExplicitPerpetratorAdmission(t)) return reply;

        String capturePost = "게시물 URL·작성자 계정·게시 시간을 캡처하고 공개 범위를 확인";
        String capturePhoto = "게시된 사진의 URL·작성자 계정·게시 시간을 캡처하고, 누가 올렸는지와 공개 범위를 확인";
        return reply
                .replace("sns에 올린 사진을 삭제하고, sns의 설정을 확인해 보는 거예요.",
                        "게시된 사진의 URL·작성자 계정·게시 시간을 캡처하고, 누가 올렸는지와 공개 범위를 확인해 보세요.")
                .replace("SNS에 올린 사진을 삭제하고, SNS의 설정을 확인해 보는 거예요.",
                        "게시된 사진의 URL·작성자 계정·게시 시간을 캡처하고, 누가 올렸는지와 공개 범위를 확인해 보세요.")
                .replace("사진을 삭제하고, SNS 설정을 확인해 보세요.",
                        "게시 화면과 작성자 정보를 캡처하고, 공개 범위와 게시자를 확인해 보세요.")
                .replace("사진을 삭제하고 SNS 설정을 확인해 보세요.",
                        "게시 화면과 작성자 정보를 캡처하고, 공개 범위와 게시자를 확인해 보세요.")
                .replace("SNS에 올린 글을 삭제하는 것", capturePost + "하는 것")
                .replace("sns에 올린 글을 삭제하는 것", capturePost + "하는 것")
                .replace("SNS에 올린 게시물을 삭제하는 것", capturePost + "하는 것")
                .replace("sns에 올린 게시물을 삭제하는 것", capturePost + "하는 것")
                .replace("SNS에 올린 사진을 삭제하는 것", capturePhoto + "하는 것")
                .replace("sns에 올린 사진을 삭제하는 것", capturePhoto + "하는 것")
                .replace("SNS에 올린 글을 삭제", capturePost)
                .replace("sns에 올린 글을 삭제", capturePost)
                .replace("SNS에 올린 게시물을 삭제", capturePost)
                .replace("sns에 올린 게시물을 삭제", capturePost)
                .replace("SNS에 올린 사진을 삭제", capturePhoto)
                .replace("sns에 올린 사진을 삭제", capturePhoto);
    }

    private static boolean hasGroupChatHarassmentSignal(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return containsAny(t, "단체 채팅방", "단톡", "단톡방", "채팅방", "카톡")
                && containsAny(t, "욕", "욕설", "놀림", "비방", "모욕", "괴롭", "따돌", "협박", "조롱");
    }

    private static boolean hasGroupChatEvidencePreservationAdvice(String reply) {
        return containsAny(reply, "참여자 목록", "보낸 시간", "앞뒤 대화", "앞뒤 맥락", "대화 내보내기", "원본", "캡처");
    }

    private static boolean asksMaleVictimCanConsult(String text) {
        return containsAny(text, "남자인데", "남자도", "남학생인데", "남자 학생")
                && containsAny(text, "상담", "말해도", "도움", "되나요", "괜찮");
    }

    private String buildFallbackReply(List<Message> history, PromptContext promptContext) {
        if (promptContext.mode() == ReplyMode.CONVERSATION) {
            return buildConversationalFallbackReply(history);
        }

        String lawContext = promptContext.lawContext();
        String combined = history.stream()
                .filter(message -> "user".equals(message.getRole()))
                .map(Message::getContent)
                .collect(Collectors.joining(" "))
                .trim();
        if (combined.isBlank()) return "";

        List<String> types = detectTypes(combined);
        ReportReadiness readiness = assessReadiness(history);
        AnalysisResult analysis = analysisService.analyze(combined, readiness);
        List<String> citations = selectFallbackCitations(lawContext);
        if (citations.isEmpty()) return "";

        StringBuilder reply = new StringBuilder();
        reply.append(buildSituationSummary(combined, analysis.violenceTypes(), readiness))
                .append("\n\n");

        reply.append("🔎 현재 판단\n");
        fallbackFindings(combined, analysis, readiness)
                .forEach(item -> reply.append("• ").append(item).append("\n"));

        reply.append("\n⚖️ 관련 법률\n");
        citations.forEach(citation -> reply.append("• ").append(citation)
                .append(": 위 사실관계와 직접 연결되는지 확인할 수 있습니다.\n"));

        reply.append("\n🗂️ 증거 확보\n");
        for (String evidence : analysis.evidenceGuide().stream().limit(4).toList()) {
            reply.append("• ").append(evidence).append("\n");
        }

        reply.append("\n💬 다음 단계\n");
        for (String action : fallbackActions(readiness, analysis, combined)) {
            reply.append("• ").append(action).append("\n");
        }

        if (!readiness.ready() && !readiness.missingInfo().isEmpty()) {
            reply.append("\n아래 질문은 지금 상담 흐름에서 가장 필요한 확인입니다. 선택지에 없어도 직접 적어도 됩니다.\n");
        } else if (shouldSuggestReport(readiness, history)) {
            reply.append("\n상담 내용이 어느 정도 정리됐습니다. 원하면 지금까지 내용을 리포트로 정리할 수 있습니다.\n");
        } else {
            reply.append("\n방금 내용은 상담 기록에 반영했습니다. 더 떠오르는 사실이 있으면 이어서 적어 주세요.\n");
        }

        return reply.toString().trim();
    }

    private String buildConversationalFallbackReply(List<Message> history) {
        String latest = latestUserMessage(history);
        String combined = history.stream()
                .filter(message -> "user".equals(message.getRole()))
                .map(Message::getContent)
                .collect(Collectors.joining(" "))
                .trim();
        ReportReadiness readiness = assessReadiness(history);
        boolean perpetratorContext = isPerpetratorContext(combined);

        if (isGreetingOnly(latest)) {
            return buildGreetingReply();
        }

        if (hasBodilyWasteIncidentSignal(latest) && !hasRealityCheckAnswer(combined)) {
            return """
                    말해준 내용은 표현이 매우 특이해서, 실제로 있었던 일인지 비유나 장난 표현인지 먼저 확인해야 합니다.
                    실제 사건이라면 신체적 모욕이나 위해가 될 수 있어 목격자, 시간, 장소를 정확히 남기는 쪽이 중요합니다.
                    아래에 실제 사건 여부를 확인할 선택지를 띄워둘게요.
                    """.trim();
        }

        if (hasNonRealEventAnswer(latest)) {
            return """
                    실제 사건이 아니라 비유나 농담 표현이라면 학교폭력 리포트로 만들 내용은 아닙니다.
                    실제로 겪은 괴롭힘이나 신고가 필요한 일이 따로 있다면, 그 일을 기준으로 새로 적어 주세요.
                    """.trim();
        }

        if (asksMaleVictimCanConsult(latest)) {
            return """
                    남자여도 당연히 상담해도 됩니다. 원하지 않은 성적 접촉이나 말은 성별과 관계없이 도움을 요청할 수 있는 일입니다.
                    지금은 자세한 표현을 억지로 하지 않아도 되고, 안전하게 말할 수 있는 범위에서 어떤 일이 있었는지만 조금씩 정리하면 됩니다.
                    """.trim();
        }

        if (hasSexualViolationSignal(latest)) {
            return """
                    말하기 부끄럽고 불편할 수 있지만, 원하지 않은 성적 접촉은 혼자 넘길 일이 아닙니다.
                    지금은 상대를 직접 찾아가 따지기보다, 기억나는 시간·장소·주변에 있던 사람을 짧게 적어두고 믿을 수 있는 어른이나 117 상담으로 안전하게 연결하는 쪽이 좋습니다.
                    """.trim();
        }

        if (isPhysicalViolenceSignal(latest)) {
            return buildPhysicalViolenceFallbackReply(latest, readiness);
        }

        if (isConfirmationAnswer(latest)) {
            String confirmationReply = buildConfirmationFallbackReply(latest, readiness);
            if (!confirmationReply.isBlank()) return confirmationReply;
        }

        if (perpetratorContext) {
            if (!hasCaseFactSignal(latest) && !isAcknowledgement(latest) && !isEmotionalConversation(latest)) {
                return """
                        지금 상담은 본인이 한 행동과 피해 회복을 기준으로 정리 중입니다.
                        방금 말한 내용이 이 사안에 반영할 내용이면 어떤 행동을 중단했는지, 사과나 삭제를 했는지처럼 연결해서 말해 주세요.
                        전혀 다른 사안이면 새 상담으로 분리하는 편이 리포트가 섞이지 않습니다.
                        """.trim();
            }
            if (containsAny(latest, "내가 피해", "제가 피해", "당했", "맞았어", "욕먹", "괴롭힘 당")) {
                return """
                        지금까지의 상담은 본인이 한 행동이 있는 사안으로 정리되어 있습니다.
                        피해를 당한 별도 사안이라면 새 상담으로 분리하는 게 맞고, 같은 사안에서 서로 충돌이 있었던 내용이면 그 관계를 분명히 적어야 합니다.
                        같은 사안 안에서 있었던 일인가요, 아니면 별도 사안인가요?
                        """.trim();
            }
            if (isAcknowledgement(latest)) {
                return """
                        알겠습니다. 지금까지 내용은 가해 또는 연루 가능성 관점으로 상담 기록에 남아 리포트에 반영됩니다.
                        이어서 말할 때는 본인이 한 행동, 중단한 조치, 사과나 피해 회복 상황을 중심으로 적어 주세요.
                        """.trim();
            }
            return """
                    이 내용은 기존 가해 또는 연루 가능성 사안에 추가로 반영하겠습니다.
                    리포트에서는 본인이 한 행동, 피해 회복 노력, 재발 방지 계획이 일관되게 정리됩니다.
                    지금 기준으로는 추가 행동을 멈추고, 보호자나 담임에게 사실관계를 숨기지 않고 공유하는 것이 우선입니다.
                    """.trim();
        }

        if (containsAny(latest, "고마워", "감사", "알겠", "응", "네", "ㅇㅋ", "오케이")) {
            if (readiness.ready()) {
                return "알겠습니다. 지금까지 확인된 내용은 상담 기록에 남아 있습니다.\n추가로 생각나는 변화가 있으면 이어서 말해 주세요. 원하면 나중에 이 흐름을 리포트로 정리할 수 있습니다.";
            }
            return "알겠습니다. 아직은 몇 가지 맥락을 더 확인하는 단계입니다.\n생각나는 만큼만 이어서 말해 주면, 그 내용까지 상담 기록에 반영하겠습니다.";
        }

        if (containsAny(latest, "힘들", "무서", "불안", "짜증", "괴롭", "말하기", "모르겠", "걱정")) {
            return """
                    바로 분석부터 밀어붙이지 않고, 먼저 상황을 정리해 보겠습니다.
                    지금 말한 감정과 어려움도 상담 기록에 남고, 나중에 내용을 정리할 때 피해 정도나 대응 필요성을 판단하는 단서가 됩니다.
                    당장 한 가지만 고르면 됩니다. 지금 가장 걱정되는 게 상대의 보복, 증거 부족, 학교에 말하는 것 중 어디에 가까운가요?
                    """.trim();
        }

        if (readiness.ready()) {
            return """
                    방금 말한 내용은 기존 상담에 추가로 반영됩니다.
                    원하면 현재까지의 대화 전체를 기준으로 유형, 증거 상태, 권장 조치를 정리할 수 있습니다.
                    더 이어서 말해도 되고, 새 사건이라면 새 상담으로 분리하는 게 좋습니다.
                    """.trim();
        }

        return """
                이 내용도 상담 기록에 반영해 두겠습니다.
                지금은 사건 내용, 학교 관계, 시점, 증거 중 빠진 부분을 더 확인하는 단계입니다.
                편하게 이어서 말해 주세요. 길게 정리하지 않아도 됩니다.
                """.trim();
    }

    private String buildConfirmationFallbackReply(String latest, ReportReadiness readiness) {
        String t = latest == null ? "" : latest;
        if (hasRealityCheckAnswer(t)) {
            if (hasNonRealEventAnswer(t)) {
                return "실제 사건이 아니라면 이 내용은 리포트로 만들지 않는 게 맞습니다. 실제로 겪은 괴롭힘이나 도움이 필요한 일이 있으면 그 내용을 기준으로 다시 정리해 주세요.";
            }
            return "실제로 있었던 일이라는 점을 먼저 확인했습니다. 상황이 드문 만큼, 시간·장소·목격자·당시 대응을 최대한 구체적으로 남겨 두는 것이 중요합니다.";
        }
        if (hasUnknownActorSignal(t)) {
            return "상대를 지금 특정하기 어렵다는 점도 상담 기록에 반영됩니다. 이 경우 같은 질문을 반복하기보다, 게시물 URL·계정 화면·시간·댓글 흐름처럼 남아 있는 단서부터 보관하는 쪽이 중요합니다.";
        }
        if (hasSexualViolationSignal(t)) {
            return "원하지 않은 신체 접촉이었다는 점이 상담 기록에 반영됩니다. 자세한 표현을 억지로 하지 않아도 되고, 기억나는 시간·장소·주변에 있던 사람을 짧게 남겨두는 것이 도움이 됩니다.";
        }
        if (containsAny(t, "같은 반", "같은 학교", "선후배", "학원 관계")) {
            return "상대와의 관계가 확인됐습니다. 같은 공간에서 계속 마주칠 수 있는 관계라서, 이후 대응은 혼자 직접 부딪히기보다 보호자나 담임을 통해 정리하는 쪽이 안전합니다.";
        }
        if (containsAny(t, "캡처", "참여자 목록", "보낸 시간", "앞뒤 대화", "증거는 거의 다")) {
            return "증거가 어느 정도 남아 있다는 점은 중요합니다. 지금은 캡처를 지우거나 편집하지 말고, 날짜와 참여자가 보이는 원본 형태로 따로 보관해 두는 게 좋습니다.";
        }
        if (containsAny(t, "불안", "두려", "등교", "보복", "큰 영향 없음", "이미 알림")) {
            return "지금 느끼는 영향도 리포트에 반영됩니다. 불안이나 보복 걱정이 있다면 대응 순서는 증거 정리보다 안전 확보가 먼저입니다.";
        }
        if (containsAny(t, "한 번", "여러 번", "며칠", "몇 주", "반복", "지속", "지금도 계속", "기억나지")) {
            return "반복된 기간도 상담 기록에 반영했습니다. 며칠 동안 이어진 일이라면 단순히 지나간 일로 넘기기보다, 날짜별로 언제 어떤 일이 있었는지 짧게 남겨 두는 게 좋습니다.";
        }
        if (readiness.ready()) {
            return "방금 답변까지 상담 기록에 반영했습니다. 리포트에는 지금까지 말한 관계, 증거, 영향이 함께 계산됩니다.";
        }
        return "방금 답변은 상담 기록에 반영했습니다. 필요한 내용은 이어지는 대화 안에서 자연스럽게 더 보완하면 됩니다.";
    }

    private String buildGreetingReply() {
        return "안녕하세요. 어떤 일이 있었는지 편하게 적어 주세요. 학교폭력 유형과 필요한 확인 내용은 제가 정리하겠습니다.";
    }

    private String buildPhysicalViolenceFallbackReply(String latest, ReportReadiness readiness) {
        String t = latest == null ? "" : latest;
        if (containsAny(t, "멍", "상처", "다쳤", "통증", "맞", "폭행", "밀쳤")) {
            return """
                    학교에서 맞고 멍까지 들었다면, 먼저 몸 상태와 안전을 확인하는 게 우선입니다.
                    멍이 보이는 부위는 오늘 날짜가 남도록 사진으로 남기고, 통증이 있거나 머리·얼굴을 맞았다면 보호자에게 바로 말해 진료 여부를 확인하세요.
                    같은 공간에서 다시 마주칠 수 있다면 혼자 상대를 찾아가 따지기보다 보호자나 담임에게 상황과 사진을 함께 보여주는 쪽이 안전합니다.
                    """.trim();
        }
        return "";
    }

    private String latestUserMessage(List<Message> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            Message message = history.get(i);
            if ("user".equals(message.getRole())) return message.getContent() == null ? "" : message.getContent().trim();
        }
        return "";
    }

    private static boolean isConversationStopped(List<Message> history) {
        if (history == null) return false;
        return history.stream()
                .filter(message -> "assistant".equals(message.getRole()))
                .map(Message::getContent)
                .filter(Objects::nonNull)
                .anyMatch(content -> content.contains("__대화가 중단 되었습니다.__")
                        || content.contains("**대화가 중단되었습니다.**")
                        || content.contains("대화가 중단되었습니다."));
    }

    static boolean shouldGuardIrrelevantInput(String latestUserMessage, boolean conversationalFollowUp) {
        return shouldGuardIrrelevantInput(latestUserMessage, false, conversationalFollowUp);
    }

    static boolean shouldGuardIrrelevantInput(String latestUserMessage, boolean hasConversationContext, boolean conversationalFollowUp) {
        String answerText = confirmationAnswerBody(latestUserMessage);
        if (isExplicitOffTopicNonsense(answerText)) return true;
        if (isConfirmationAnswer(latestUserMessage)) return false;
        if (hasConversationContext) return false;
        return isIrrelevantInput(answerText) && !conversationalFollowUp;
    }

    private static boolean hasConversationContext(List<Message> history) {
        return userMessageCount(history) > 1;
    }

    private static boolean isConfirmationAnswer(String text) {
        String t = text == null ? "" : text.trim();
        return t.startsWith("확인 답변:")
                || t.startsWith("확인답변:")
                || t.startsWith("답변:")
                || t.startsWith("추가 설명:")
                || t.startsWith("추가설명:");
    }

    private static String confirmationAnswerBody(String text) {
        String t = text == null ? "" : text.trim();
        if (t.startsWith("확인 답변:")) return t.substring("확인 답변:".length()).trim();
        if (t.startsWith("확인답변:")) return t.substring("확인답변:".length()).trim();
        if (t.startsWith("답변:")) return t.substring("답변:".length()).trim();
        if (t.startsWith("추가 설명:")) return t.substring("추가 설명:".length()).trim();
        if (t.startsWith("추가설명:")) return t.substring("추가설명:".length()).trim();
        return t;
    }

    private static boolean isIrrelevantInput(String text) {
        String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (t.isBlank()) return false;
        if (t.length() <= 1) return true;
        if (isConsultationMetaQuestion(t)) return false;
        if (isGreetingOnly(t)) return false;
        if (hasConsultationSignal(t)) return false;
        if (isExplicitNonsenseOrSmallTalk(t)) return true;
        if (isAcknowledgement(t) || isEmotionalConversation(t)) return false;

        return true;
    }

    private static boolean isGreetingOnly(String text) {
        String compact = text == null ? "" : text.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}~!！。]+", "");
        return compact.matches("^(안녕|안녕하세요|안녕하세용|안녕하십니까|하이|하이요|헬로|ㅎㅇ|hi|hello)$");
    }

    private static boolean isExplicitOffTopicNonsense(String text) {
        String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (isGreetingOnly(t)) return false;
        return !t.isBlank() && !hasConsultationSignal(t) && isExplicitNonsenseOrSmallTalk(t);
    }

    private static boolean hasConsultationSignal(String text) {
        return hasDemeaningSpeechSignal(text) || containsAny(text,
                "학교", "같은 반", "반 친구", "친구", "선배", "후배", "학생", "담임", "선생", "학원",
                "때렸", "맞았", "폭행", "밀쳤", "멍", "상처", "욕", "모욕", "비방", "협박", "따돌", "왕따",
                "괴롭", "괴롭힘", "놀림", "놀렸", "놀려", "비하", "무시", "소외", "패드립",
                "sns", "카톡", "단톡", "채팅", "채팅방", "dm", "디엠", "게시", "댓글", "사진", "성추행", "성희롱",
                "성적으로", "신체 접촉", "원하지 않는", "만졌", "만지는", "불쾌", "갈취", "스토킹",
                "가해", "피해", "증거", "캡처", "신고", "117", "리포트", "상담", "확인 질문",
                "용의자", "특정", "누군지", "누구인지", "작성자", "계정",
                "걱정", "무서", "두려", "불안", "보복", "반복", "재발", "또 그럴", "다시 그럴",
                "또 쌀", "쌀까", "똥도 쌌", "똥 쌌", "목격자", "도망", "얼굴", "몸", "배설물", "대변", "소변");
    }

    private static boolean isConsultationMetaQuestion(String text) {
        boolean explicitQuestion = containsAny(text, "어떤 확인", "무슨 확인", "뭐 확인", "뭘 확인", "무엇을 확인",
                "어떤 질문", "무슨 질문", "뭐 물어", "뭘 물어", "추가 질문", "추가 확인",
                "뭘 더", "뭐가 더");
        boolean asksWhatIsNeeded = containsAny(text, "필요한가", "필요해", "필요하")
                && containsAny(text, "확인", "질문", "리포트", "상담", "어떤", "무슨", "무엇", "뭐", "뭘");
        return explicitQuestion || asksWhatIsNeeded;
    }

    private static boolean isExplicitNonsenseOrSmallTalk(String text) {
        String compact = text.replaceAll("[\\s\\p{Punct}]+", "");
        if (compact.matches("^(똥+|응가+|오줌+|방귀+|쉬+)$")) return true;
        if (containsAny(text,
                "똥싸", "똥 쌌", "응가", "오줌", "방귀", "뭐먹", "배고파", "졸려", "잠와", "심심",
                "게임", "롤 ", "발로란트", "마크", "유튜브", "노래", "아이돌", "날씨", "농담",
                "ㅋㅋ", "ㅎㅎ", "ㅗ", "ㅅㅂ", "ㅂㅅ", "씨발", "시발", "병신", "개소리", "헛소리", "아무거나", "아무말")) {
            return true;
        }
        return compact.matches("^[ㅋㅎㅠㅜㅡㅇㄴㄱㄷㄹㅁㅂㅅㅈㅊㅍㅎ]{2,}$");
    }

    private String buildIrrelevantInputReply() {
        return buildConversationStoppedReply("학교폭력 상담과 무관한 입력입니다.");
    }

    private String buildPerpetratorOffTopicReply() {
        return buildConversationStoppedReply("본인이 한 행동과 피해 회복 상담에 무관한 입력입니다.");
    }

    private String buildConversationStoppedReply(String reason) {
        return """
                **대화가 중단되었습니다.**
                사유 : %s
                새 상담이 필요하면 새 상담으로 다시 시작해 주세요.
                """.formatted(reason).trim();
    }

    private static String stopReasonForInput(String text) {
        String body = confirmationAnswerBody(text);
        if (isConfirmationAnswer(text)) {
            return "확인 질문에 학교폭력 상담과 무관한 답변이 입력되었습니다.";
        }
        if (isExplicitNonsenseOrSmallTalk(body)) {
            return "학교폭력 상담과 무관한 장난성 또는 의미 없는 입력입니다.";
        }
        return "학교폭력 상담과 직접 관련 없는 입력입니다.";
    }

    private boolean isConversationalFollowUp(String text, List<Message> history) {
        if (userMessageCount(history) <= 1) return false;
        return isAcknowledgement(text)
                || isEmotionalConversation(text)
                || isConsultationMetaQuestion(text)
                || containsAny(text, "어떡", "어떻게", "어떤", "무엇", "뭐", "괜찮", "말해", "얘기", "상담", "리포트", "기록", "반영");
    }

    private static boolean isAcknowledgement(String text) {
        return containsAny(text, "고마워", "감사", "알겠", "응", "네", "ㅇㅋ", "오케이", "맞아", "그래");
    }

    private static boolean isEmotionalConversation(String text) {
        return containsAny(text,
                "힘들", "무서", "불안", "짜증", "괴롭", "말하기", "모르겠", "걱정", "답답", "억울",
                "부끄", "창피", "민망", "쪽팔", "망설", "말 못", "말하기 싫", "하고 싶은데", "하고싶은데");
    }

    private String callGroqApi(List<Message> history, String systemPrompt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(groqApiKey);

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        for (Message message : recentHistory(history, AI_HISTORY_LIMIT)) {
            messages.add(Map.of(
                    "role", "assistant".equals(message.getRole()) ? "assistant" : "user",
                    "content", message.getContent()
            ));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        String model = groqModel == null || groqModel.isBlank()
                ? "llama-3.1-8b-instant"
                : groqModel.trim();
        body.put("model", model);
        body.put("messages", messages);
        body.put("max_completion_tokens", Math.max(400, Math.min(groqMaxCompletionTokens, 1200)));
        body.put("temperature", 0.1);

        ResponseEntity<Map> res = aiClient.exchange(
                "https://api.groq.com/openai/v1/chat/completions",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class
        );
        return extractOpenAiStyleResponse(res.getBody());
    }

    private String callDeepSeekApi(List<Message> history, String systemPrompt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(effectiveDeepSeekApiKey());

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        for (Message message : recentHistory(history, AI_HISTORY_LIMIT)) {
            messages.add(Map.of(
                    "role", "assistant".equals(message.getRole()) ? "assistant" : "user",
                    "content", message.getContent()
            ));
        }

        String model = cheapestDeepSeekModel(deepSeekModel);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("max_tokens", effectiveDeepSeekMaxTokens(deepSeekMaxTokens));
        body.put("temperature", 0.1);
        if (model.startsWith("deepseek-v4")) {
            body.put("thinking", Map.of("type", "disabled"));
        }

        ResponseEntity<Map> res = aiClient.exchange(
                "https://api.deepseek.com/chat/completions",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class
        );
        return extractOpenAiStyleResponse(res.getBody());
    }

    static String cheapestDeepSeekModel(String configuredModel) {
        return CHEAPEST_DEEPSEEK_MODEL;
    }

    static int effectiveDeepSeekMaxTokens(int configuredMaxTokens) {
        int selected = configuredMaxTokens > 0 ? configuredMaxTokens : DEEPSEEK_DEFAULT_MAX_TOKENS;
        return Math.max(DEEPSEEK_MIN_MAX_TOKENS, Math.min(selected, DEEPSEEK_HARD_MAX_TOKENS));
    }

    private String callGeminiApi(List<Message> history, String systemPrompt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-goog-api-key", geminiApiKey);

        List<Map<String, Object>> contents = new ArrayList<>();
        for (Message message : recentHistory(history, AI_HISTORY_LIMIT)) {
            contents.add(Map.of(
                    "role", "assistant".equals(message.getRole()) ? "model" : "user",
                    "parts", List.of(Map.of("text", message.getContent()))
            ));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("system_instruction", Map.of(
                "parts", List.of(Map.of("text", systemPrompt))
        ));
        body.put("contents", contents);
        body.put("generationConfig", Map.of(
                "maxOutputTokens", 1200,
                "temperature", 0.1,
                "thinkingConfig", Map.of("thinkingBudget", 0)
        ));

        ResponseEntity<Map> res = aiClient.exchange(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class
        );
        return extractGeminiResponse(res.getBody());
    }

    private String callClaudeApi(List<Message> history, String systemPrompt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", backupApiKey);
        headers.set("anthropic-version", "2023-06-01");

        List<Map<String, String>> messages = new ArrayList<>();
        for (Message message : recentHistory(history, AI_HISTORY_LIMIT)) {
            messages.add(Map.of(
                    "role", "assistant".equals(message.getRole()) ? "assistant" : "user",
                    "content", message.getContent()
            ));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "claude-haiku-4-5-20251001");
        body.put("max_tokens", 1200);
        body.put("system", systemPrompt);
        body.put("messages", messages);

        ResponseEntity<Map> res = aiClient.exchange(
                "https://api.anthropic.com/v1/messages",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class
        );

        Map responseBody = res.getBody();
        if (responseBody == null) throw new IllegalStateException("Claude 응답이 비어 있습니다.");
        List content = (List) responseBody.get("content");
        if (content == null || content.isEmpty()) throw new IllegalStateException("Claude content가 없습니다.");
        return String.valueOf(((Map) content.get(0)).get("text"));
    }

    private String effectiveDeepSeekApiKey() {
        if (deepSeekApiKey != null && !deepSeekApiKey.isBlank()) {
            return deepSeekApiKey.trim();
        }
        if (backupApiKey == null || backupApiKey.isBlank()) {
            return "";
        }
        String key = backupApiKey.trim();
        return key.startsWith("sk-ant-") ? "" : key;
    }

    private boolean isClaudeBackupAvailable() {
        return backupApiKey != null
                && !backupApiKey.isBlank()
                && backupApiKey.trim().startsWith("sk-ant-");
    }

    private PromptContext buildPromptContext(List<Message> history) {
        String combined = history.stream()
                .filter(message -> "user".equals(message.getRole()))
                .map(Message::getContent)
                .collect(Collectors.joining(" "));
        List<String> violenceTypes = detectTypes(combined);
        long userMessages = userMessageCount(history);
        ReportReadiness readiness = assessReadiness(history);
        String latest = latestUserMessage(history);
        ReplyMode replyMode = determineReplyMode(history, latest, combined);
        String lawContext = lawApiService.getContextForCase(combined, violenceTypes);
        if (lawContext == null || lawContext.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "법령 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요."
            );
        }
        if (lawContext.length() > 2600) lawContext = lawContext.substring(0, 2600);

        String allowedCitations = formatAllowedCitations(lawContext);
        if (!readiness.ready()) {
            return new PromptContext(buildWarmIntakePrompt(readiness, allowedCitations, lawContext, history), lawContext, ReplyMode.CONVERSATION);
        }
        if (replyMode == ReplyMode.CONVERSATION) {
            return new PromptContext(buildConversationPrompt(readiness, allowedCitations, lawContext, history), lawContext, replyMode);
        }

        String openingLine = userMessages <= 1
                ? "상황 정리: 사건 유형, 학교 관계, 증거 상태를 자연스러운 한 문장으로 요약"
                : "추가로 반영한 점: 직전 답변 뒤 새로 확인된 단서 또는 판단 변화 1줄";

        String prompt = """
                당신은 학교폭력 관련 학생과 보호자를 돕는 한국 법률 정보 상담 AI입니다.
                목표는 뻔한 위로가 아니라, 사용자가 "내 상황이 어떤 유형이고 지금 무엇을 해야 하는지" 바로 판단하도록 돕는 것입니다.

                # 반드시 지킬 규칙
                1. 파악된 사실을 먼저 정리하고, 부족한 정보는 추측하지 말고 확인이 필요하다고만 안내하세요.
                2. 사용자 문장을 그대로 복사하거나 길게 다시 쓰지 말고, 사건 유형·관계·증거·확인 필요점으로 재구성하세요.
                3. 이전 답변과 같은 문장을 반복하지 말고, 이번 입력이 판단을 바꾼 점이나 새로 확인한 단서를 먼저 말하세요.
                4. 모든 문장은 자연스러운 한국어로 작성하세요. AI, SNS, URL, DM, CCTV, PDF, ID, IP 외 외국어 단어를 쓰지 마세요.
                5. 제3자가 사건을 평가하는 보고서 말투가 아니라, 지금 대화 중인 사람에게 직접 건네는 상담 말투로 쓰세요.
                5-0. '사용자', '피해자', '가해자' 같은 라벨로 상대를 부르지 말고, 필요하면 '지금 상황', '이 경우', '말해준 내용'처럼 표현하세요.
                5-1. 유형명은 신체 폭력, 언어 폭력, 사이버 폭력, 따돌림, 성폭력, 스토킹, 갈취 중 필요한 것만 사용하세요.
                5-2. 사용자에게 학교폭력 유형을 고르게 하지 마세요. 사용자가 말한 실제 행동을 근거로 유형은 상담 AI가 판단하고, 부족하면 행동 사실만 확인하세요.
                6. 법령명과 조문 번호는 아래 '허용 인용 목록'에서 현재 상황과 직접 관련된 항목만 최대 2개 골라 글자 그대로 복사하세요.
                7. 허용 인용 목록에 없는 법령, 조문, 조문명은 절대 쓰지 마세요. 특히 정보통신망법을 임의로 인용하지 마세요.
                8. 법률 설명은 참고 법령 내용의 범위를 벗어나 단정하지 말고, "적용 가능성이 있습니다", "확인이 필요합니다"처럼 말하세요.
                9. 가해자 나이나 형사책임을 사용자가 묻지 않았다면 소년법을 언급하지 마세요.
                10. 증거는 현재 상황에 맞는 항목 1~2개만 우선순위로 안내하세요.
                10-1. 단체 채팅방, 단톡방, 카톡 욕설·놀림 사안에서는 대화방을 나가거나 내용을 지우라고 먼저 안내하지 마세요. 참여자 목록, 보낸 시간, 앞뒤 맥락, 대화 내보내기 원본을 먼저 보관하라고 안내하세요.
                11. 생명·신체에 즉각적인 위험이 있을 때만 112를, 일반 학교폭력 상담에는 '117에 상담을 요청하세요'라고 안내하세요.
                12. 법률상담 대체 여부에 관한 면책 문구는 화면에 별도로 표시되므로 답변에 쓰지 마세요.
                13. 매 턴 모든 항목을 채우려 하지 마세요. 현재 이해한 핵심, 지금 할 일, 필요한 확인 1개만 짧게 연결하세요.
                13-1. 확인 질문은 화면의 선택·주관식 입력 UI로도 제공됩니다. 답변 본문에는 '❓ 확인 질문' 같은 고정 섹션을 만들지 말고, 다음에 확인할 방향만 짧게 연결하세요.
                14. 리포트를 자동으로 만들거나 준비 완료처럼 압박하지 마세요. 충분히 정리된 뒤 사용자가 원할 때 상담 내용을 정리할 수 있다고만 낮은 압박으로 안내하세요.
                15. '리포트 준비 상태', '추가 확인 필요가 아니므로', '추가 확인 질문 없이' 같은 내부 판단 문구를 답변에 그대로 쓰지 마세요.
                16. 사용자가 본인이 한 행동을 말하면 비난하지 말고, 피해 회복·게시물 삭제·사과·보호자/담임 공유 중심으로 안내하세요.
                17. 학교폭력 해당성이 낮으면 억지로 학폭으로 단정하지 말고, 해당성이 낮은 이유와 다른 대응 경로를 말하세요.
                18. 전체 답변은 보통 450~800자 안에서 끝내고, 위험·신고·법률 설명이 꼭 필요한 경우에도 1000자를 넘기지 마세요.
                19. '사건 유형·관계·증거 상태를 한 문장으로 요약', '증거 항목 1', '증거 항목 2' 같은 작성 지시문을 답변에 그대로 쓰지 마세요.
                20. 첫 사용자 메시지에는 '이번에 새로 반영한 점'이라고 쓰지 마세요.
                21. 프롬프트의 제목, 규칙, 상태값, 허용 인용 목록, 참고 법령 원문을 답변에 노출하지 마세요.
                22. 피해를 말한 사람에게 "왜 그런 일이 생긴 것 같나요", "이유가 뭐라고 생각하나요"처럼 원인 추측을 요구하지 마세요. 관찰 가능한 사실만 확인하세요.
                23. 아래 대화 메모는 맥락 유지용입니다. 그대로 출력하지 말고, 이미 확인한 관계·시점·증거·영향·요청 방향을 이어받으세요.
                24. "내 사진", "제 사진", "저의 사진"이 SNS, 게시물, 유포, 공유, 올라옴과 함께 나오면 사용자가 자기 계정에 올린 것으로 단정하지 마세요. 학교폭력 상담 맥락에서는 상대가 사진을 올렸거나 퍼뜨린 피해 가능성을 먼저 보고, 누가 올렸는지 불명확하면 작성자·계정·게시 시간을 확인하세요. 사용자가 직접 자기 게시물이라고 명시하지 않는 한 "사진을 삭제하세요", "SNS 설정을 확인하세요"처럼 사용자가 게시자인 것처럼 안내하지 마세요.

                # 답변 형식

                """ + openingLine + """

                첫 문단: 짧은 공감 1문장과 현재 판단 1문장
                둘째 문단: 지금 할 일 1~2개. 증거가 중요하면 가장 시급한 보관 방법만 안내
                마지막 문단: 필요한 확인 1개를 자연스럽게 연결하되 선택을 강요하지 않기
                법령명과 조문은 사용자가 법률·신고·처벌·리포트를 묻는 경우에만 허용 인용 목록에서 최대 1개 사용

                # 대화 메모
                """ + buildConversationMemory(history) + """

                # 리포트 준비 상태
                상태: """ + readiness.status() + """
                준비 완료: """ + readiness.ready() + """
                이유: """ + readiness.reason() + """
                부족한 정보: """ + String.join(", ", readiness.missingInfo()) + """

                # 허용 인용 목록
                """ + allowedCitations + """

                # 참고 법령
                """ + lawContext + NEXT_QUESTION_PROTOCOL;
        return new PromptContext(prompt, lawContext, replyMode);
    }

    private ReplyMode determineReplyMode(List<Message> history, String latest, String combined) {
        long userMessages = userMessageCount(history);
        if (userMessages <= 1) {
            return (isAcknowledgement(latest) || isEmotionalConversation(latest))
                    ? ReplyMode.CONVERSATION
                    : ReplyMode.ANALYSIS;
        }
        if (asksForStructuredAnalysis(latest)) return ReplyMode.ANALYSIS;
        if (containsSevereNewIncident(latest)) return ReplyMode.ANALYSIS;
        if (isPerpetratorContext(combined)) return ReplyMode.CONVERSATION;
        if (isAcknowledgement(latest) || isEmotionalConversation(latest)) return ReplyMode.CONVERSATION;
        if (!hasCaseFactSignal(latest)) return ReplyMode.CONVERSATION;
        if (containsAny(latest, "사과", "삭제", "말했", "얘기했", "상담했", "알렸", "보관", "캡처했", "기록했")) {
            return ReplyMode.CONVERSATION;
        }
        return ReplyMode.ANALYSIS;
    }

    private boolean asksForStructuredAnalysis(String text) {
        return containsAny(text, "법", "법률", "신고", "117", "112", "리포트", "분석", "증거", "조치", "처벌", "어떻게 해야", "어떡해야");
    }

    private boolean containsSevereNewIncident(String text) {
        return containsAny(text, "또 맞", "또 때", "때렸", "맞았", "출혈", "골절", "응급실", "흉기", "칼",
                "던졌", "던지", "우유곽", "들이붓", "끼얹", "부었", "뿌렸",
                "성추행", "성폭행", "성적으로", "원하지 않는", "만졌", "자살", "자해", "죽고 싶", "보복", "협박");
    }

    private boolean isPhysicalViolenceSignal(String text) {
        return containsAny(text, "맞았", "맞고", "때렸", "폭행", "밀쳤", "멍", "상처", "통증", "다쳤",
                "던졌", "던지", "우유곽", "들이붓", "끼얹", "부었", "뿌렸");
    }

    private boolean hasCaseFactSignal(String text) {
        return hasDemeaningSpeechSignal(text) || containsAny(text,
                "학교", "같은 반", "반 친구", "친구", "선배", "후배", "학생", "담임", "선생", "학원",
                "때렸", "맞았", "폭행", "밀쳤", "멍", "상처", "욕", "모욕", "비방", "협박", "따돌", "왕따",
                "던졌", "던지", "우유곽", "들이붓", "끼얹", "부었", "뿌렸",
                "sns", "카톡", "단톡", "dm", "디엠", "게시", "댓글", "사진", "성추행", "성희롱", "성적으로",
                "신체 접촉", "원하지 않는", "만졌", "불쾌", "갈취", "스토킹",
                "가해", "피해", "캡처", "url", "진단", "병원", "목격", "사과", "삭제", "보호자");
    }

    private boolean isPerpetratorContext(String text) {
        return isPerpetratorText(text);
    }

    private static boolean isPerpetratorText(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        boolean victimContext = hasVictimContext(t);
        boolean ownPhotoPostedVictimContext = hasOwnPhotoPostedVictimContext(t);
        boolean directPhrase = containsAny(t,
                "제가 때렸", "내가 때렸", "제가 밀쳤", "내가 밀쳤",
                "제가 욕했", "내가 욕했", "제가 욕을 했", "내가 욕을 했",
                "제가 올렸", "내가 올렸", "제가 올린", "내가 올린", "제가 사진을 올렸", "내가 사진을 올렸",
                "제가 게시", "내가 게시", "제가 댓글", "내가 댓글",
                "제가 괴롭혔", "내가 괴롭혔", "제가 따돌", "내가 따돌",
                "저도 같이 욕", "같이 욕했", "장난으로 올렸",
                "사과하고 싶", "제가 처벌받", "내가 처벌받",
                "제가 가해", "내가 가해", "나는 가해", "저는 가해", "본인이 가해",
                "제가 가해자", "내가 가해자", "나는 가해자", "저는 가해자",
                "가해자입니다", "가해를 했", "제가 한 행동", "내가 한 행동", "괴롭힌 건 저");
        if (ownPhotoPostedVictimContext && !hasExplicitPerpetratorAdmission(t)) return false;
        if (directPhrase) return true;
        if (victimContext) return false;
        boolean selfActor = containsAny(t, "제가", "내가", "저도", "본인이");
        boolean harmfulAction = containsAny(t,
                "때렸", "밀쳤", "욕했", "욕을 했", "욕설을 했", "올렸", "게시", "댓글을 달",
                "괴롭혔", "따돌", "놀렸", "빼앗", "강요");
        return selfActor && harmfulAction;
    }

    private static boolean hasVictimContext(String text) {
        return containsAny(text,
                "피해를 당", "피해 당", "제가 피해", "저는 피해", "내가 피해", "제가 당", "내가 당",
                "맞아서", "맞았고", "맞았어요", "욕을 먹", "욕먹", "괴롭힘 당", "괴롭힘을 당",
                "신고하려", "신고하고 싶", "사과 받고", "사과 받", "처벌받게", "상대가 저를", "친구가 저를",
                "제 사진", "내 사진", "저의 사진", "나의 사진", "저한테", "저에게");
    }

    private static boolean hasOwnPhotoPostedVictimContext(String text) {
        return containsAny(text, "제 사진", "내 사진", "저의 사진", "나의 사진")
                && containsAny(text, "sns", "인스타", "게시", "올렸", "올린", "올라왔", "유포", "공유", "퍼졌");
    }

    private static boolean hasExplicitPerpetratorAdmission(String text) {
        return containsAny(text,
                "저는 가해", "제가 가해", "내가 가해", "나는 가해", "본인이 가해",
                "저는 가해자", "제가 가해자", "내가 가해자", "나는 가해자",
                "제가 친구 사진", "내가 친구 사진", "제가 상대 사진", "내가 상대 사진",
                "허락 없이 올렸", "몰래 올렸", "장난으로 올렸",
                "제가 한 행동", "내가 한 행동", "괴롭힌 건 저");
    }

    private String buildWarmIntakePrompt(ReportReadiness readiness, String allowedCitations, String lawContext, List<Message> history) {
        String combined = history.stream()
                .filter(message -> "user".equals(message.getRole()))
                .map(Message::getContent)
                .collect(Collectors.joining(" "));
        boolean perpetratorContext = isPerpetratorContext(combined);
        String roleInstruction = perpetratorContext
                ? """
                - 사용자가 본인이 한 행동을 말하면 비난하지 말고, 중단·삭제 전 원본 보존·보호자/담임 공유·사과 방식·재발 방지 순서로 차분히 안내하세요.
                - 피해자를 탓하거나 행동을 정당화하지 말고, 책임을 회복 가능한 행동으로 바꾸는 방향을 제시하세요.
                """
                : """
                - 사용자가 피해나 두려움을 말하면 먼저 그 감정을 인정하고, 혼자 감당하지 않아도 된다는 메시지를 분명히 전하세요.
                - 보복, 협박, 접근 위험이 보이면 안전 확보와 보호자·담임·117 연결을 우선 안내하세요.
                """;

        return """
                당신은 학교폭력 상황을 듣는 따뜻한 상담자입니다. 지금 목표는 리포트를 빨리 만드는 것이 아니라,
                사용자가 안전하게 말할 수 있도록 돕고 리포트에 필요한 사실을 자연스럽게 확인하는 것입니다.

                # 답변 방식
                1. 첫 문장은 사용자의 감정이나 부담을 먼저 받아주세요.
                2. 사용자가 말한 내용을 복사하지 말고, 핵심만 1문장으로 부드럽게 정리하세요.
                3. 지금 바로 할 수 있는 행동은 직전 입력에 맞게 우선순위 1~2개만 안내하세요. 증거가 사라질 수 있는 상황이면 가장 시급한 행동을 먼저 말하세요.
                4. 답변은 짧은 공감, 현재 이해한 핵심, 지금 할 일, 필요한 확인 1개로만 구성하세요. 법적 쟁점과 유형은 꼭 필요한 경우 한 문장으로만 언급하세요. 이미 말한 내용은 다시 묻지 마세요.
                5. 선생님이나 어른이 채팅방에 있는지 묻지 마세요. 단체 채팅방 사안에서 필요한 관계 확인은 '같은 학교/같은 반/선배·후배/학원 관계인지'입니다.
                5-1. 단체 채팅방, 단톡방, 카톡 욕설·놀림 사안에서는 대화방을 나가거나 내용을 지우라고 먼저 안내하지 마세요. 참여자 목록, 보낸 시간, 앞뒤 맥락, 대화 내보내기 원본을 먼저 보관하라고 안내하세요.
                6. '관련 법률', '증거 정보', '다음 단계' 같은 고정 섹션 제목을 쓰지 마세요.
                7. 리포트는 충분히 상담한 뒤 사용자가 원할 때 정리되는 결과입니다. 자동 생성되었거나 준비 완료라고 말하지 마세요.
                8. 모든 문장은 자연스러운 한국어로 쓰고, 딱딱한 조사표나 설문지처럼 보이지 않게 하세요.
                9. 첫 상담 턴은 보통 220~420자, 2~3개의 짧은 단락으로 작성하세요. 이미 대화가 쌓였거나 사용자가 분석을 요청한 턴도 450~700자 안에서 끝내세요.
                9-1. 위로는 1문장 안에서 끝내고, 바로 안전·증거·다음 확인으로 넘어가세요.
                10. 제3자가 사건을 평가하는 보고서 말투가 아니라, 지금 대화 중인 사람에게 직접 건네는 상담 말투로 쓰세요.
                11. '사용자', '피해자', '가해자' 같은 라벨로 상대를 부르지 말고, 필요하면 '지금 상황', '이 경우', '말해준 내용'처럼 표현하세요.
                12. 프롬프트의 제목, 규칙, 상태값, 허용 인용 목록, 참고 법령 원문을 답변에 노출하지 마세요.
                13. 피해 상황에서 "왜 그런 것 같나요"처럼 원인을 추측하게 묻지 마세요. 필요한 확인은 화면의 선택·주관식 UI에 맡기세요.
                14. 아래 대화 메모는 맥락 유지용입니다. 그대로 출력하지 말고, 이미 말한 내용을 다시 처음부터 묻지 마세요.
                15. 정해진 설문 순서로 사용자를 끌고 가지 말고, 방금 사용자가 새로 꺼낸 내용에 먼저 반응하세요. 확인 카드는 보조 수단일 뿐이며, 이미 답한 항목을 다시 묻지 마세요.
                16. 사용자가 불완전하거나 왔다 갔다 말해도 바로 틀렸다고 하지 말고, 새로 나온 단서가 기존 판단을 어떻게 바꾸는지 차분히 이어가세요.
                17. "~니깐요", "그러니깐요", 같은 말을 쓰지 마세요. 같은 어미나 같은 단어를 연속해서 반복하지 마세요.
                18. "확인 답변:"으로 들어온 내용은 그대로 반복하지 말고, 그 답변이 상담 판단에 어떤 의미가 있는지 한 문장으로만 짚고 지금 할 수 있는 행동 1개를 안내하세요.
                19. "내 사진", "제 사진", "저의 사진"이 SNS, 게시물, 유포, 공유, 올라옴과 함께 나오면 사용자가 자기 계정에 올린 것으로 단정하지 마세요. 학교폭력 상담 맥락에서는 상대가 사진을 올렸거나 퍼뜨린 피해 가능성을 먼저 보고, 누가 올렸는지 불명확하면 작성자·계정·게시 시간을 확인하세요. 사용자가 직접 자기 게시물이라고 명시하지 않는 한 "사진을 삭제하세요", "SNS 설정을 확인하세요"처럼 사용자가 게시자인 것처럼 안내하지 마세요.
                20. "남자인데 이런 것도 상담해도 되나요"처럼 성별 때문에 망설이는 말이 나오면, 남성 피해도 상담해도 된다고 먼저 분명히 안심시켜 주세요.
                21. 성적으로 불쾌한 말이나 원하지 않은 접촉은 신체 폭력의 "맞음, 밀침, 넘어짐, 상처" 선택지로 돌리지 말고 성폭력/성추행 맥락으로 이어가세요.
                22. 답변 전에 먼저 주된 상담 유형을 하나 정하세요. 성폭력, 신체 폭력, 사이버 폭력, 따돌림, 스토킹, 갈취, 가해·연루 중 현재 맥락과 맞지 않는 유형명·선택지·행동 지침은 쓰지 마세요.
                23. 사용자에게 "어떤 유형인지"를 고르게 하지 마세요. 실제 행동을 듣고 유형은 상담 AI가 판단하세요.

                """ + roleInstruction + """

                # 대화 메모
                """ + buildConversationMemory(history) + """

                # 현재 리포트 준비 상태
                상태: """ + readiness.status() + """
                이유: """ + readiness.reason() + """
                아직 더 필요한 정보: """ + String.join(", ", readiness.missingInfo()) + """

                # 인용 가능한 법령 목록
                """ + allowedCitations + """

                # 참고 법령 원문
                """ + lawContext + NEXT_QUESTION_PROTOCOL;
    }

    private String buildConversationPrompt(ReportReadiness readiness, String allowedCitations, String lawContext, List<Message> history) {
        String combined = history.stream()
                .filter(message -> "user".equals(message.getRole()))
                .map(Message::getContent)
                .collect(Collectors.joining(" "));
        boolean perpetratorContext = isPerpetratorContext(combined);
        String roleInstruction = perpetratorContext
                ? """
                # 가해 또는 연루 관점 고정 규칙
                - 현재 상담은 본인이 한 행동을 기준으로 정리 중입니다. 갑자기 피해자 관점으로 바꾸거나 상대를 가해자로 단정하지 마세요.
                - 대화는 피해 회복, 추가 행동 중단, 게시물 삭제, 사과 방식, 보호자/담임 공유, 재발 방지 계획으로 돌려야 합니다.
                - 사용자가 전혀 다른 말을 하면 잡담을 이어가지 말고, 이 내용이 같은 사안에 반영할 내용인지 또는 새 상담으로 분리할 내용인지 짧게 확인하세요.
                """
                : """
                # 대화 관점 규칙
                - 사용자가 감정 표현, 망설임, 짧은 확인, 상황 공유를 하면 먼저 대화로 받아주세요.
                - 새로 말한 내용은 상담 기록과 리포트에 반영된다고 설명하되, 리포트가 확정됐다고 말하지 마세요.
                """;

        return """
                당신은 학교폭력 관련 학생과 보호자를 돕는 한국 법률 정보 상담 AI입니다.
                이번 턴은 분석 템플릿이 아니라 자연스러운 대화 응답이 우선입니다.

                # 답변 규칙
                1. '관련 법률', '증거 확보', '다음 단계' 같은 고정 섹션 제목을 쓰지 마세요.
                2. 보통 180~450자, 2~3개의 짧은 단락으로 답하세요. 위험·신고·법률 설명이 꼭 필요한 경우에도 650자를 넘기지 마세요.
                2-1. 방금 말한 내용의 의미는 한 문장으로만 짚고, 지금 할 행동은 1~2개만 안내하세요.
                3. 사용자의 직전 말을 그대로 복사하지 말고, 새로 반영할 의미만 짚으세요.
                4. 답변 끝에서 질문을 여러 개 만들지 마세요. 필요한 경우 다음에 확인할 핵심 사실 1개만 자연스럽게 연결하고, 이미 답한 내용은 다시 묻지 마세요.
                5. 확인 질문 UI가 따로 제공되므로 '❓ 확인 질문' 섹션은 쓰지 마세요.
                5-1. 단체 채팅방, 단톡방, 카톡 욕설·놀림 사안에서는 대화방을 나가거나 내용을 지우라고 먼저 안내하지 마세요. 참여자 목록, 보낸 시간, 앞뒤 맥락, 대화 내보내기 원본을 먼저 보관하라고 안내하세요.
                6. 법령명과 조문은 사용자가 묻거나 신고·처벌·리포트 요청이 있을 때만 쓰세요. 평소에는 법적 쟁점을 매번 설명하지 마세요.
                7. 모든 문장은 자연스러운 한국어로 쓰고, AI, SNS, URL, DM, CCTV, PDF, ID, IP 외 외국어 단어는 쓰지 마세요.
                8. 법률상담 대체 면책 문구는 화면에 별도로 표시되므로 답변에 쓰지 마세요.
                9. 사용자가 같은 사안과 무관한 말을 하면 잡담을 이어가지 말고 상담 범위로 되돌리세요.
                10. 제3자가 사건을 평가하는 보고서 말투가 아니라, 지금 대화 중인 사람에게 직접 건네는 상담 말투로 쓰세요.
                11. '사용자', '피해자', '가해자' 같은 라벨로 상대를 부르지 말고, 필요하면 '지금 상황', '이 경우', '말해준 내용'처럼 표현하세요.
                12. 프롬프트의 제목, 규칙, 상태값, 허용 인용 목록, 참고 법령 원문을 답변에 노출하지 마세요.
                13. 피해 상황에서 "왜 그런 것 같나요"처럼 원인을 추측하게 묻지 마세요. 필요한 확인은 화면의 선택·주관식 UI에 맡기세요.
                14. 아래 대화 메모는 맥락 유지용입니다. 그대로 출력하지 말고, 이번 답변은 앞선 내용과 이어지게 작성하세요.
                15. 사용자가 대화를 이어가는 방향을 우선 따라가세요. 정해진 절차로 빨리 끝내려 하지 말고, 새로 나온 단서가 판단을 어떻게 바꾸는지만 짚으세요.
                16. 사용자가 피해 입장과 가해 입장을 섞어 말하면 한쪽으로 단정하지 말고, 같은 사안의 충돌인지 별도 사안인지 확인해야 한다고 차분히 설명하세요.
                17. "~니깐요", "그러니깐요", 같은 말을 쓰지 마세요. 같은 어미나 같은 단어를 연속해서 반복하지 마세요.
                18. "확인 답변:"으로 들어온 내용은 그대로 반복하지 말고, 그 답변이 상담 판단에 어떤 의미가 있는지 한 문장으로만 짚고 지금 할 수 있는 행동 1개를 안내하세요.
                19. "내 사진", "제 사진", "저의 사진"이 SNS, 게시물, 유포, 공유, 올라옴과 함께 나오면 사용자가 자기 계정에 올린 것으로 단정하지 마세요. 학교폭력 상담 맥락에서는 상대가 사진을 올렸거나 퍼뜨린 피해 가능성을 먼저 보고, 누가 올렸는지 불명확하면 작성자·계정·게시 시간을 확인하세요. 사용자가 직접 자기 게시물이라고 명시하지 않는 한 "사진을 삭제하세요", "SNS 설정을 확인하세요"처럼 사용자가 게시자인 것처럼 안내하지 마세요.
                20. "남자인데 이런 것도 상담해도 되나요"처럼 성별 때문에 망설이는 말이 나오면, 남성 피해도 상담해도 된다고 먼저 분명히 안심시켜 주세요.
                21. "지금 당장 할 수 있는 작은 행동 2가지", "상담 내용을 다시 한번 확인" 같은 고정 문구를 반복하지 마세요. 위로는 1문장 안에서 끝내세요.
                22. 성적으로 불쾌한 말이나 원하지 않은 접촉은 신체 폭력의 "맞음, 밀침, 넘어짐, 상처" 선택지로 돌리지 말고 성폭력/성추행 맥락으로 이어가세요.
                23. 답변 전에 먼저 주된 상담 유형을 하나 정하세요. 성폭력, 신체 폭력, 사이버 폭력, 따돌림, 스토킹, 갈취, 가해·연루 중 현재 맥락과 맞지 않는 유형명·선택지·행동 지침은 쓰지 마세요.
                24. 사용자에게 "어떤 유형인지"를 고르게 하지 마세요. 실제 행동을 듣고 유형은 상담 AI가 판단하세요.

                """ + roleInstruction + """

                # 대화 메모
                """ + buildConversationMemory(history) + """

                # 리포트 반영 상태
                상태: """ + readiness.status() + """
                준비 완료: """ + readiness.ready() + """
                이유: """ + readiness.reason() + """
                부족한 정보: """ + String.join(", ", readiness.missingInfo()) + """

                # 허용 인용 목록
                """ + allowedCitations + """

                # 참고 법령
                """ + lawContext + NEXT_QUESTION_PROTOCOL;
    }

    private ReportReadiness assessReadiness(List<Message> history) {
        String combined = combinedUserText(history);
        if (hasBodilyWasteIncidentSignal(combined) && hasNonRealEventAnswer(combined)) {
            return new ReportReadiness(
                    false,
                    "상담 내용 확인 필요",
                    "실제 사건이 아닌 비유나 농담 표현으로 확인되어 리포트 생성 대상이 아닙니다.",
                    List.of(),
                    List.of("비유 또는 농담 표현 확인"),
                    false
            );
        }
        ReportReadiness raw = analysisService.assessReportReadiness(combined, userMessageCount(history));
        return applyHistoryAnswerState(raw, combined, userMessageCount(history), history);
    }

    static ReportReadiness applyHistoryAnswerState(
            ReportReadiness readiness,
            String combinedText,
            long userMessageCount,
            List<Message> history
    ) {
        if (readiness == null || readiness.ready() || readiness.missingInfo().isEmpty()) return readiness;
        if ("상담 내용 확인 필요".equals(readiness.status())) return readiness;

        String text = combinedText == null ? "" : combinedText;
        Set<String> answeredFamilies = answeredQuestionFamilies(history);
        List<String> remainingMissing = new ArrayList<>();
        List<String> answeredFacts = new ArrayList<>();

        for (String missing : readiness.missingInfo()) {
            if (isMissingInfoAnsweredByHistory(missing, text, userMessageCount, answeredFamilies)) {
                answeredFacts.add(factForMissingInfo(missing));
            } else {
                remainingMissing.add(missing);
            }
        }

        if (remainingMissing.size() == readiness.missingInfo().size()) return readiness;

        List<String> keyFacts = new ArrayList<>(readiness.keyFacts());
        answeredFacts.stream()
                .filter(fact -> fact != null && !fact.isBlank())
                .forEach(keyFacts::add);

        if (!remainingMissing.isEmpty()) {
            return new ReportReadiness(
                    false,
                    readiness.status(),
                    readiness.reason(),
                    remainingMissing.stream().distinct().toList(),
                    keyFacts.stream().distinct().toList(),
                    readiness.schoolViolenceLikely()
            );
        }

        String status = effectiveReadyStatus(readiness, text);
        String reason = effectiveReadyReason(readiness, status);
        boolean schoolViolenceLikely = !"학교폭력 해당성 낮음".equals(status);
        return new ReportReadiness(
                true,
                status,
                reason,
                List.of(),
                keyFacts.stream().distinct().toList(),
                schoolViolenceLikely
        );
    }

    private static boolean isMissingInfoAnsweredByHistory(
            String missingInfo,
            String text,
            long userMessageCount,
            Set<String> answeredFamilies
    ) {
        String family = missingInfoFamily(missingInfo);
        if ("conversation_depth".equals(family)) {
            return hasCoreReportAnswers(text, answeredFamilies);
        }
        if (family.isBlank()) return false;
        return answeredFamilies.contains(family) || isConfirmationCandidateAnswered(family, text);
    }

    private static boolean hasCoreReportAnswers(String text, Set<String> answeredFamilies) {
        return hasReportAnswer("incident", text, answeredFamilies)
                && hasReportAnswer("relationship", text, answeredFamilies)
                && hasReportAnswer("timeline", text, answeredFamilies)
                && hasReportAnswer("evidence", text, answeredFamilies)
                && hasReportAnswer("impact", text, answeredFamilies)
                && hasReportAnswer("goal", text, answeredFamilies);
    }

    private static boolean hasReportAnswer(String family, String text, Set<String> answeredFamilies) {
        return answeredFamilies.contains(family) || isConfirmationCandidateAnswered(family, text);
    }

    private static Set<String> answeredQuestionFamilies(List<Message> history) {
        Set<String> answered = new HashSet<>();
        if (history == null || history.isEmpty()) return answered;
        String pendingFamily = "";

        for (Message message : history) {
            if (message == null) continue;
            String role = message.getRole();
            String content = message.getContent() == null ? "" : message.getContent().trim();
            if ("assistant".equals(role)) {
                pendingFamily = questionFamilyFromAssistantText(content);
                continue;
            }
            if ("user".equals(role) && !pendingFamily.isBlank() && isUsableAnswerToQuestion(content)) {
                answered.add(pendingFamily);
                answered.addAll(relatedAnswerFamilies(pendingFamily));
                pendingFamily = "";
            }
        }
        return answered;
    }

    private static boolean isUsableAnswerToQuestion(String content) {
        String answer = confirmationAnswerBody(content);
        return !answer.isBlank() && !isExplicitOffTopicNonsense(answer);
    }

    private static Set<String> relatedAnswerFamilies(String family) {
        return switch (family) {
            case "incident_post", "chat_pattern" -> Set.of("incident");
            case "chat_timeline" -> Set.of("timeline");
            case "chat_scale" -> Set.of("evidence", "impact");
            case "post_trace", "evidence_chat", "evidence_threat", "physical_support", "sexual_context" -> Set.of("evidence");
            case "post_spread" -> Set.of("evidence", "impact");
            case "chat_support", "physical_injury", "physical_context", "sexual_support", "actor_stop", "threat_safety" -> Set.of("impact");
            case "actor_recovery", "threat_school_action" -> Set.of("goal");
            default -> Set.of();
        };
    }

    private static String questionFamilyFromAssistantText(String content) {
        if (content == null || content.isBlank() || !looksLikeQuestionTurn(content)) return "";
        if (hasAny(content, "실제로 있었던 행동", "실제로 어떤 행동", "온라인에 올라온 내용", "몸에 어떤 일", "어떤 유형", "유형은 제가 판단")) return "incident";
        if (hasAny(content, "상대와의 관계", "학교폭력 절차 기준", "같은 반", "같은 학교", "학교 밖", "학교 관계")) return "relationship";
        if (hasAny(content, "언제부터", "몇 번", "어떤 빈도", "한 번인지", "지금도", "반복됐나요")) return "timeline";
        if (hasAny(content, "남아 있는 증거", "증거는 무엇", "캡처", "URL", "작성자 계정", "게시 시간", "확인 가능한 게",
                "협박이나 반복 방문", "녹음", "CCTV", "방문 기록", "목격자")) return "evidence";
        if (hasAny(content, "피해 정도", "당시 상황", "맞거나 밀친 정도", "친구들이 알고", "상대가 흥분")) return "physical_context";
        if (hasAny(content, "영향", "걱정되는 부분", "보복", "등교", "불안", "두려운", "함께 알고 대응")) return "impact";
        if (hasAny(content, "필요한 도움", "어떤 도움", "신고", "증거 정리", "안전하게 보호", "거리를 둬야",
                "마주칠 가능성", "보호 조치")) return "goal";
        if (hasAny(content, "몇 명", "몇명", "참여자", "본 사람", "방에 있는 사람", "대화방 인원")) return "chat_scale";
        if (hasAny(content, "같은 사안", "리포트를 생성")) return "final_check";
        return "";
    }

    private static String missingInfoFamily(String missingInfo) {
        if (missingInfo == null) return "";
        if (missingInfo.contains("무슨 일")) return "incident";
        if (missingInfo.contains("학교 관계")) return "relationship";
        if (missingInfo.contains("언제")) return "timeline";
        if (missingInfo.contains("증거")) return "evidence";
        if (missingInfo.contains("피해 영향") || missingInfo.contains("회복")) return "impact";
        if (missingInfo.contains("원하는 도움")) return "goal";
        if (missingInfo.contains("조금 더")) return "conversation_depth";
        return "";
    }

    private static String factForMissingInfo(String missingInfo) {
        String family = missingInfoFamily(missingInfo);
        return switch (family) {
            case "incident" -> "질문 답변 이력으로 사건 내용 확인";
            case "relationship" -> "질문 답변 이력으로 상대 관계 또는 관계상 제한사항 확인";
            case "timeline" -> "질문 답변 이력으로 시점 또는 반복성 확인";
            case "evidence" -> "질문 답변 이력으로 증거 또는 발생 경로 확인";
            case "impact" -> "질문 답변 이력으로 피해 영향 또는 보호 필요성 확인";
            case "goal" -> "질문 답변 이력으로 요청한 도움 방향 확인";
            case "conversation_depth" -> "핵심 확인 항목이 충족되어 리포트 생성 가능";
            default -> "";
        };
    }

    private static String effectiveReadyStatus(ReportReadiness readiness, String text) {
        if (!"추가 확인 필요".equals(readiness.status())) return readiness.status();
        if (isPerpetratorText(text)) return "가해 또는 연루 가능성 검토";
        if (!hasLikelySchoolRelationship(text) || !hasViolenceTypeSignal(text)) return "학교폭력 해당성 낮음";
        return "학교폭력 가능성 검토";
    }

    private static String effectiveReadyReason(ReportReadiness readiness, String status) {
        if ("학교폭력 해당성 낮음".equals(status)) {
            return "현재 정보만으로는 학교 관계 또는 학교폭력 유형과의 연결이 약합니다.";
        }
        if ("추가 확인 필요".equals(readiness.status())) {
            return "사용자의 확인 답변 이력을 바탕으로 제한사항을 포함해 리포트를 생성할 수 있습니다.";
        }
        return readiness.reason();
    }

    private static boolean hasLikelySchoolRelationship(String text) {
        return hasAny(text, "같은 반", "같은 학교", "반 친구", "학교 친구", "학교 학생", "선후배", "선배", "후배", "학원 관계", "우리 학교")
                || hasSchoolLocationContext(text);
    }

    private static boolean hasSchoolLocationContext(String text) {
        return hasAny(text,
                "학교에서", "학교 안", "학교 내", "교내", "교실", "복도", "운동장", "급식실", "화장실",
                "보건실", "등교", "하교", "학교 앞", "학교 주변", "쉬는 시간", "점심시간", "수업 중", "방과 후");
    }

    private static boolean hasViolenceTypeSignal(String text) {
        return hasPhysicalViolenceSignal(text)
                || hasSexualViolationSignal(text)
                || hasPostSignal(text)
                || hasChatSignal(text)
                || hasSocialExclusionSignal(text)
                || hasExtortionSignal(text)
                || hasStalkingSignal(text)
                || hasDemeaningSpeechSignal(text)
                || hasAny(text, "욕", "협박", "모욕", "비하", "비방", "놀림", "소문", "명예훼손");
    }

    private static String buildConversationMemory(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return "아직 누적된 대화가 없습니다.";
        }

        List<String> userMessages = history.stream()
                .filter(message -> "user".equals(message.getRole()))
                .map(Message::getContent)
                .filter(Objects::nonNull)
                .map(ChatService::compactWhitespace)
                .filter(content -> !content.isBlank())
                .toList();
        if (userMessages.isEmpty()) {
            return "아직 사용자가 말한 사건 정보가 없습니다.";
        }

        StringBuilder memory = new StringBuilder();
        memory.append("- 전체 사안 사용자 진술: ")
                .append(compactForMemory(String.join(" / ", userMessages), 900))
                .append("\n");

        int from = Math.max(0, userMessages.size() - MEMORY_RECENT_USER_LIMIT);
        memory.append("- 최근 추가된 사용자 내용:\n");
        for (int i = from; i < userMessages.size(); i += 1) {
            memory.append("  ")
                    .append(i + 1)
                    .append(". ")
                    .append(compactForMemory(userMessages.get(i), 180))
                    .append("\n");
        }

        String lastAssistant = latestAssistantMessage(history);
        if (!lastAssistant.isBlank()) {
            memory.append("- 직전 답변 요지: ")
                    .append(compactForMemory(lastAssistant, 260))
                    .append("\n");
        }
        memory.append("- 연결 규칙: 이미 확인한 관계, 시점, 증거, 영향, 원하는 도움은 다시 처음부터 묻지 말고 이어받으세요. 새 내용이 기존 사안과 충돌하면 같은 사안인지 분리할 사안인지 확인하세요.");
        return memory.toString().trim();
    }

    private static String latestAssistantMessage(List<Message> history) {
        if (history == null) return "";
        for (int i = history.size() - 1; i >= 0; i -= 1) {
            Message message = history.get(i);
            if ("assistant".equals(message.getRole())) {
                return compactWhitespace(message.getContent());
            }
        }
        return "";
    }

    private static String compactForMemory(String text, int maxLength) {
        String compacted = compactWhitespace(text);
        if (compacted.length() <= maxLength) return compacted;
        return compacted.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private static String compactWhitespace(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private static String combinedUserText(List<Message> history) {
        if (history == null) return "";
        return history.stream()
                .filter(message -> "user".equals(message.getRole()))
                .map(Message::getContent)
                .collect(Collectors.joining(" "))
                .trim();
    }

    private static long userMessageCount(List<Message> history) {
        if (history == null) return 0;
        return history.stream().filter(message -> "user".equals(message.getRole())).count();
    }

    private String formatAllowedCitations(String lawContext) {
        Map<String, Set<String>> allowed = parseAllowedCitations(lawContext);
        StringBuilder result = new StringBuilder();
        allowed.forEach((law, articles) -> articles.forEach(article ->
                result.append("- ").append(law).append(" 제").append(article).append("조\n")));
        return result.toString().trim();
    }

    private GeneratedReply requireValidGeneratedReply(String reply, PromptContext promptContext) {
        GeneratedReply parsed = parseGeneratedReply(reply);
        String sanitized = sanitizeUnsupportedLegalReferences(
                sanitizeGeneratedReply(parsed.reply()),
                promptContext.lawContext()
        );
        if (sanitized.isBlank()
                || !usesAllowedCharacters(sanitized)
                || containsForbiddenTemplatePhrase(sanitized)
                || hasUnsafePhysicalViolenceAdvice(sanitized)) {
            throw new IllegalStateException("AI 응답이 안전 또는 법령 검증을 통과하지 못했습니다.");
        }
        return new GeneratedReply(sanitized, parsed.followUpQuestion());
    }

    static GeneratedReply parseGeneratedReply(String rawReply) {
        if (rawReply == null || rawReply.isBlank()) return new GeneratedReply("", null);
        Matcher matcher = NEXT_QUESTION_BLOCK_PATTERN.matcher(rawReply);
        if (!matcher.find()) {
            int start = rawReply.indexOf(NEXT_QUESTION_START);
            if (start < 0) return new GeneratedReply(rawReply.trim(), null);

            String visibleReply = rawReply.substring(0, start).trim();
            String danglingBlock = rawReply.substring(start + NEXT_QUESTION_START.length());
            ConfirmationCandidate followUp = parseAiFollowUpQuestion(danglingBlock);
            return new GeneratedReply(visibleReply, followUp);
        }

        String visibleReply = (rawReply.substring(0, matcher.start()) + "\n" + rawReply.substring(matcher.end())).trim();
        ConfirmationCandidate followUp = parseAiFollowUpQuestion(matcher.group(1));
        return new GeneratedReply(visibleReply, followUp);
    }

    private static ConfirmationCandidate parseAiFollowUpQuestion(String block) {
        if (block == null || block.isBlank()) return null;
        String question = "";
        List<Map<String, String>> options = new ArrayList<>();

        for (String rawLine : block.split("\\R")) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isBlank() || line.equals("선택지:")) continue;
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("질문:") || lower.startsWith("question:")) {
                question = line.substring(line.indexOf(':') + 1).trim();
                continue;
            }
            String optionLine = line.replaceFirst("^[-*•]\\s*", "").replaceFirst("^\\d+[.)]\\s*", "").trim();
            if (optionLine.isBlank() || optionLine.equals(line) && !line.contains("|")) continue;
            Map<String, String> option = parseAiOption(optionLine);
            if (option != null) options.add(option);
        }

        if ("없음".equals(question) || question.length() > 120) return null;
        options = options.stream()
                .filter(option -> !option.getOrDefault("label", "").isBlank())
                .filter(option -> !option.getOrDefault("message", "").isBlank())
                .limit(4)
                .toList();
        if (question.isBlank() && !options.isEmpty()) {
            question = "지금 바로 확인할 수 있는 내용은 무엇인가요?";
        }
        if (question.isBlank()) return null;
        if (options.isEmpty()) {
            options = List.of(option("직접 입력", "확인 답변: "));
        }
        return new ConfirmationCandidate("ai_next_question", question, options);
    }

    private static Map<String, String> parseAiOption(String optionLine) {
        if (optionLine == null || optionLine.isBlank()) return null;
        String[] parts = optionLine.split("\\|", 2);
        String label = normalizeAiConfirmationLabel(parts[0].trim());
        String message = parts.length > 1 ? parts[1].trim() : "";
        if (label.endsWith(":")) label = label.substring(0, label.length() - 1).trim();
        if (message.isBlank()) message = "확인 답변: " + label;
        if (!message.startsWith("확인 답변:") && !message.startsWith("추가 설명:")) {
            message = "확인 답변: " + message;
        }
        message = normalizeAiConfirmationMessage(message);
        if (label.length() > 80) label = label.substring(0, 80).trim();
        return option(label, message);
    }

    private static String normalizeAiConfirmationLabel(String label) {
        String normalized = normalizeAiConfirmationSentence(label);
        if (normalized.endsWith(".")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    private static String normalizeAiConfirmationMessage(String message) {
        String normalized = message == null ? "" : message.trim().replaceAll("\\s+", " ");
        String prefix = "";
        if (normalized.startsWith("확인 답변:")) {
            prefix = "확인 답변: ";
            normalized = normalized.substring("확인 답변:".length()).trim();
        } else if (normalized.startsWith("추가 설명:")) {
            prefix = "추가 설명: ";
            normalized = normalized.substring("추가 설명:".length()).trim();
        }
        if (normalized.isBlank()) return prefix;
        return prefix + normalizeAiConfirmationSentence(normalized);
    }

    static String normalizeAiConfirmationSentence(String text) {
        String normalized = text == null ? "" : text.trim().replaceAll("\\s+", " ");
        if (normalized.isBlank()) return "";
        normalized = normalized
                .replaceAll("고\\s*있다고\\s*함$", "고 있습니다")
                .replaceAll("고\\s*있다고\\s*했음$", "고 있습니다")
                .replaceAll("라고\\s*함$", "라고 말했습니다")
                .replaceAll("다고\\s*함$", "다고 말했습니다")
                .replaceAll("\\s*함$", "했습니다");
        normalized = normalized
                .replaceAll("고\\s+있다고\\s+답함$", "고 있습니다")
                .replaceAll("고\\s+있다고\\s+함$", "고 있습니다")
                .replaceAll("라고\\s+답함$", "라고 답했습니다")
                .replaceAll("라고\\s+함$", "라고 답했습니다")
                .replaceAll("다고\\s+답함$", "다고 답했습니다")
                .replaceAll("다고\\s+함$", "다고 답했습니다")
                .replaceAll("확인함$", "확인했습니다")
                .replaceAll("파악함$", "파악했습니다")
                .replaceAll("알림$", "알렸습니다")
                .replaceAll("삭제함$", "삭제했습니다")
                .replaceAll("중단함$", "중단했습니다")
                .replaceAll("겪음$", "겪고 있습니다")
                .replaceAll("공유됨$", "공유됐습니다")
                .replaceAll("유포됨$", "유포됐습니다")
                .replaceAll("퍼짐$", "퍼졌습니다")
                .replaceAll("못\\s*함$", "하지 못했습니다")
                .replaceAll("있음$", "있습니다")
                .replaceAll("없음$", "없습니다")
                .replaceAll("상황$", "상황입니다");
        if (normalized.matches(".*(습니다|입니다|했습니다|됐습니다|합니다|됩니다)$")) {
            normalized += ".";
        }
        return normalized;
    }

    static boolean isGeneratedConversationReplyValid(String reply, String lawContext) {
        if (reply == null || reply.isBlank() || reply.length() > 2600) return false;
        if (containsForbiddenTemplatePhrase(reply)) return false;
        if (!usesAllowedCharacters(reply)) return false;
        if (hasUnsafePhysicalViolenceAdvice(reply)) return false;
        return hasOnlyAllowedLegalReferences(reply, lawContext);
    }

    private static boolean hasOnlyAllowedLegalReferences(String reply, String lawContext) {
        Map<String, Set<String>> allowed = parseAllowedCitations(lawContext);
        for (String marker : KNOWN_LAW_MARKERS) {
            if (reply.contains(marker) && allowed.keySet().stream().noneMatch(
                    law -> law.equals(marker) || isSupportedAlias(marker, law))) {
                return false;
            }
        }
        for (String line : reply.split("\\R")) {
            List<String> references = extractArticleReferences(line);
            if (references.isEmpty()) continue;
            String law = findLawForLine(line, allowed.keySet());
            if (law == null || !allowed.get(law).containsAll(references)) return false;
        }
        return true;
    }

    private static boolean hasUncontrolledQuestion(String reply) {
        String text = reply == null ? "" : reply.trim();
        if (text.contains("?")) return true;
        return containsAny(text,
                "있으신가요", "있나요", "인가요", "인가요.", "되나요", "될까요",
                "알려주실 수", "말해주실 수", "이야기해주실 수", "말씀해주실 수",
                "말해 줄 수", "이야기해 줄 수");
    }

    private static boolean hasUnsafePhysicalViolenceAdvice(String reply) {
        String text = reply == null ? "" : reply;
        boolean physicalContext = containsAny(text, "맞", "멍", "폭행", "상처", "통증", "밀쳤", "신체");
        if (!physicalContext) return false;
        return containsAny(text, "혼자서도 괜찮", "물 많이", "따뜻한 물", "목욕", "잘 쉬");
    }

    static boolean isGeneratedReplyValid(String reply, String lawContext) {
        if (reply == null || reply.isBlank() || reply.length() > 3600) return false;
        if (containsForbiddenTemplatePhrase(reply)) return false;
        if (!usesAllowedCharacters(reply)) return false;
        if (hasUnsafePhysicalViolenceAdvice(reply)) return false;
        return hasOnlyAllowedLegalReferences(reply, lawContext);
    }

    private static boolean containsForbiddenTemplatePhrase(String reply) {
        return reply.contains("사건 유형·관계·증거 상태를 한 문장으로 요약")
                || reply.contains("증거 항목 1")
                || reply.contains("증거 항목 2")
                || reply.contains("증거 항목 3")
                || reply.contains("허용 인용 목록")
                || reply.contains("참고 법령")
                || reply.contains("# 답변 형식")
                || reply.contains("# 답변 방식")
                || reply.contains("# 답변 규칙")
                || reply.contains("현재 리포트 준비 상태")
                || reply.contains("화면에 별도로 제공될 확인 질문")
                || reply.contains("인용 가능한 법령 목록")
                || reply.contains("참고 법령 원문")
                || reply.startsWith("상태:")
                || reply.contains("\n상태:")
                || reply.startsWith("준비 완료:")
                || reply.contains("\n준비 완료:")
                || reply.startsWith("부족한 정보:")
                || reply.contains("\n부족한 정보:")
                || reply.contains("이번에 새로 반영한 점")
                || reply.contains("확인된 유형: 학교폭력, 사이버폭력")
                || reply.contains("확인된 유형: 학교폭력")
                || reply.contains("그 이유는 무엇이라고 생각")
                || reply.contains("왜 그런 일이 생긴 것 같")
                || reply.contains("왜 괴롭힌다고 생각")
                || reply.contains("괴롭히는 이유")
                || containsDanglingQuestionLeadIn(reply)
                || reply.contains("지금 당장 할 수 있는 작은 행동 2가지")
                || reply.contains("상담 내용을 다시 한번 확인")
                || reply.contains("상담 내용을 다시 한 번 확인")
                || reply.contains("어떤 부분이 특히 어려운지 다시")
                || reply.contains("맞음, 밀침, 넘어짐, 상처")
                || reply.contains("선생님이나 어른이 계신가요")
                || reply.contains("채팅방에 참여하고 있는 친구들 중에 선생님")
                || containsUnsafeGroupChatExitAdvice(reply)
                || (reply.contains("채팅방") && (reply.contains("어른") || reply.contains("선생님"))
                && (reply.contains("있") || reply.contains("계신")));
    }

    static String sanitizeGeneratedReply(String reply) {
        if (reply == null) return "";
        return sanitizeGroupChatExitAdvice(stripConfirmationQuestionSection(reply)).lines()
                .filter(line -> !(line.contains("일반적인 법률 정보") && line.contains("대체하지")))
                .filter(line -> !(line.contains("리포트 준비 상태") && line.contains("추가 확인 필요")))
                .filter(line -> !line.contains("추가 확인 질문 없이"))
                .filter(line -> !line.contains("추가 확인 필요가 아니므로"))
                .filter(line -> !containsDanglingQuestionLeadIn(line))
                .filter(line -> !containsRemovableBadReplyPhrase(line))
                .filter(line -> !containsForbiddenTemplatePhrase(line))
                .filter(line -> !hasUnsafePhysicalViolenceAdvice(line))
                .map(ChatService::removeUncontrolledQuestionSentences)
                .map(line -> line
                        .replace("니깐요", "니까요")
                        .replace("사용자가", "말해준 내용이")
                        .replace("사용자는", "지금은")
                        .replace("피해자는", "피해를 겪은 쪽은")
                        .replace("가해자는", "행동한 쪽은")
                        .replace("117와", "117에")
                        .replace("117과", "117에")
                        .replace("당신의", "해당")
                        .replace("당신은 ", ""))
                .collect(Collectors.joining("\n"))
                .trim();
    }

    static String sanitizeGroupChatExitAdvice(String reply) {
        if (reply == null || reply.isBlank() || !containsUnsafeGroupChatExitAdvice(reply)) return reply == null ? "" : reply;

        Matcher matcher = UNSAFE_GROUP_CHAT_EXIT_SENTENCE_PATTERN.matcher(reply);
        StringBuffer sanitized = new StringBuffer();
        boolean replaced = false;
        while (matcher.find()) {
            replaced = true;
            matcher.appendReplacement(sanitized, Matcher.quoteReplacement(GROUP_CHAT_EVIDENCE_FIRST_ADVICE));
        }
        matcher.appendTail(sanitized);

        String result = replaced ? sanitized.toString() : GROUP_CHAT_EVIDENCE_FIRST_ADVICE;
        while (result.contains(GROUP_CHAT_EVIDENCE_FIRST_ADVICE + "\n" + GROUP_CHAT_EVIDENCE_FIRST_ADVICE)) {
            result = result.replace(GROUP_CHAT_EVIDENCE_FIRST_ADVICE + "\n" + GROUP_CHAT_EVIDENCE_FIRST_ADVICE,
                    GROUP_CHAT_EVIDENCE_FIRST_ADVICE);
        }
        return result.trim();
    }

    private static boolean containsUnsafeGroupChatExitAdvice(String reply) {
        String text = reply == null ? "" : reply.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return containsAny(text,
                "단체 채팅방에서 나가", "단체 채팅방을 나가",
                "단톡방에서 나가", "단톡방을 나가", "단톡에서 나가", "단톡을 나가",
                "채팅방에서 나가", "채팅방을 나가", "대화방에서 나가", "대화방을 나가",
                "채팅방 나가기", "대화방 나가기", "단톡방 나가기",
                "채팅방에서 퇴장", "대화방에서 퇴장", "단톡방에서 퇴장",
                "채팅방에서 빠져나오", "대화방에서 빠져나오", "단톡방에서 빠져나오");
    }

    static String sanitizeUnsupportedLegalReferences(String reply, String lawContext) {
        if (reply == null || reply.isBlank()) return "";
        Map<String, Set<String>> allowed = parseAllowedCitations(lawContext);
        return reply.lines()
                .filter(line -> isAllowedLegalReferenceLine(line, allowed))
                .collect(Collectors.joining("\n"))
                .trim();
    }

    private static boolean isAllowedLegalReferenceLine(String line, Map<String, Set<String>> allowed) {
        if (line == null || line.isBlank()) return true;
        for (String marker : KNOWN_LAW_MARKERS) {
            if (line.contains(marker) && allowed.keySet().stream().noneMatch(
                    law -> law.equals(marker) || isSupportedAlias(marker, law))) {
                return false;
            }
        }
        List<String> references = extractArticleReferences(line);
        if (references.isEmpty()) return true;
        String law = findLawForLine(line, allowed.keySet());
        return law != null && allowed.get(law).containsAll(references);
    }

    private static boolean containsRemovableBadReplyPhrase(String line) {
        return containsAny(line,
                "지금 당장 할 수 있는 작은 행동 2가지",
                "상담 내용을 다시 한번 확인",
                "상담 내용을 다시 한 번 확인",
                "어떤 부분이 특히 어려운지 다시",
                "맞음, 밀침, 넘어짐, 상처",
                "선생님이나 어른이 계신가요",
                "채팅방에 참여하고 있는 친구들 중에 선생님")
                || (line.contains("채팅방")
                && (line.contains("어른") || line.contains("선생님"))
                && (line.contains("있") || line.contains("계신")));
    }

    private static String removeUncontrolledQuestionSentences(String line) {
        if (line == null || line.isBlank()) return "";
        StringBuilder kept = new StringBuilder();
        Matcher sentenceMatcher = Pattern.compile("[^.!?。？]+[.!?。？]?").matcher(line);
        while (sentenceMatcher.find()) {
            String sentence = sentenceMatcher.group();
            String trimmed = sentence.trim();
            if (trimmed.isBlank()) continue;
            if (hasUncontrolledQuestion(trimmed)) continue;
            if (!kept.isEmpty()) kept.append(' ');
            kept.append(trimmed);
        }
        return kept.toString();
    }

    private static String stripConfirmationQuestionSection(String reply) {
        StringBuilder result = new StringBuilder();
        for (String line : reply.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.contains("확인 질문") || trimmed.startsWith("❓")) {
                break;
            }
            result.append(line).append("\n");
        }
        return result.toString().trim();
    }

    private static boolean containsDanglingQuestionLeadIn(String text) {
        if (text == null || text.isBlank()) return false;
        return containsAny(text,
                "다시 한번 물어보겠습니다",
                "다시 한 번 물어보겠습니다",
                "물어보겠습니다",
                "질문드리겠습니다",
                "질문하겠습니다",
                "확인하겠습니다")
                && !text.contains("?");
    }

    private static Map<String, Set<String>> parseAllowedCitations(String lawContext) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        String currentLaw = null;
        for (String line : lawContext.split("\\R")) {
            Matcher header = LAW_HEADER_PATTERN.matcher(line.trim());
            if (header.matches()) {
                currentLaw = header.group(1).trim();
                result.putIfAbsent(currentLaw, new LinkedHashSet<>());
                continue;
            }
            if (currentLaw != null) {
                Matcher article = ARTICLE_REFERENCE_PATTERN.matcher(line.trim());
                if (article.lookingAt()) {
                    String base = firstNonNull(article.group(1), article.group(3), article.group(5));
                    String detail = firstNonNull(article.group(2), article.group(4));
                    result.get(currentLaw).add(detail == null ? base : base + "의" + detail);
                }
            }
        }
        result.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        return result;
    }

    private static List<String> extractArticleReferences(String text) {
        List<String> references = new ArrayList<>();
        Matcher matcher = ARTICLE_REFERENCE_PATTERN.matcher(text);
        while (matcher.find()) {
            String base = firstNonNull(matcher.group(1), matcher.group(3), matcher.group(5));
            String detail = firstNonNull(matcher.group(2), matcher.group(4));
            references.add(detail == null ? base : base + "의" + detail);
        }
        return references;
    }

    private static List<String> selectFallbackCitations(String lawContext) {
        Map<String, Set<String>> allowed = parseAllowedCitations(lawContext);
        List<String> citations = new ArrayList<>();
        allowed.forEach((law, articles) -> articles.stream().limit(1)
                .forEach(article -> citations.add(law + " 제" + article + "조")));
        return citations.stream().limit(2).toList();
    }

    private static String buildSituationSummary(String text, List<String> types, ReportReadiness readiness) {
        String typeText = types == null || types.isEmpty()
                ? "아직 유형이 명확하지 않은 상담"
                : String.join(", ", types) + " 단서가 있는 상담";
        String context = contextSignal(text);
        if (!readiness.ready()) {
            return typeText + "으로 정리됩니다. " + context + " 리포트 생성 전 핵심 사실을 더 확인해야 합니다.";
        }
        if (!readiness.schoolViolenceLikely()) {
            return typeText + "이지만, " + context + " 학교폭력 절차로 볼 수 있는지는 추가 확인이 필요합니다.";
        }
        return typeText + "으로 볼 수 있습니다. " + context + " 증거와 다음 조치를 바로 정리해야 합니다.";
    }

    private static List<String> fallbackFindings(String text, AnalysisResult analysis, ReportReadiness readiness) {
        List<String> findings = new ArrayList<>();
        findings.add("확인된 유형: " + (analysis.violenceTypes().isEmpty() ? "아직 불명확" : String.join(", ", analysis.violenceTypes())));
        findings.add("위험 신호: " + riskBand(analysis.riskScore()) + " 단계로 보이며, 점수는 " + analysis.riskScore() + "/10입니다.");
        findings.add(evidenceSignal(text));
        if (!readiness.reason().isBlank()) {
            findings.add("리포트 판단: " + readiness.reason());
        }
        return findings.stream().distinct().limit(4).toList();
    }

    private static String riskBand(double score) {
        if (score >= 7) return "높음";
        if (score >= 4) return "중간";
        return "낮음";
    }

    private static String contextSignal(String text) {
        if (hasAny(text, "같은 반", "반 친구", "동급생")) return "상대가 같은 반 또는 학교 관계자로 언급됐습니다.";
        if (hasAny(text, "학교", "선배", "후배", "학생", "담임", "선생", "학원")) return "학교 또는 학원 관계 단서가 있습니다.";
        return "상대와 학교 관계는 아직 분명하지 않습니다.";
    }

    private static String evidenceSignal(String text) {
        List<String> signals = new ArrayList<>();
        if (hasAny(text, "캡처", "스크린샷")) signals.add("캡처");
        if (hasAny(text, "url", "링크", "게시글 번호")) signals.add("URL");
        if (hasAny(text, "사진", "영상")) signals.add("사진·영상");
        if (hasAny(text, "진단", "병원", "치료")) signals.add("진단·치료 기록");
        if (hasAny(text, "목격")) signals.add("목격자");
        if (signals.isEmpty()) return "증거 상태: 아직 구체적으로 확인되지 않았습니다.";
        return "증거 상태: " + String.join(", ", signals) + " 단서가 있습니다.";
    }

    private static List<String> fallbackActions(ReportReadiness readiness, AnalysisResult analysis, String text) {
        if (!readiness.ready()) {
            return List.of(
                    "아래 확인 질문에 답하면 리포트 판단이 바로 갱신됩니다.",
                    firstEvidenceAction(analysis.evidenceGuide())
            );
        }
        if (!readiness.schoolViolenceLikely()) {
            return List.of(
                    "학교 관계가 있는 사건인지 먼저 확인하세요.",
                    "학교폭력으로 보기 어렵다면 플랫폼 신고나 일반 상담 경로를 함께 검토하세요."
            );
        }
        if (readiness.status().contains("가해")) {
            return List.of(
                    "게시물이나 발언이 남아 있다면 즉시 중단하고 삭제 여부를 확인하세요.",
                    "보호자나 담임에게 사실관계를 숨기지 말고 설명하고 피해 회복 방안을 정리하세요."
            );
        }
        if (hasAny(text, "계속", "아직도", "협박", "찾아가", "때리겠")) {
            return List.of(
                    "보호자나 담임에게 오늘 안에 상황과 증거를 공유하세요.",
                    "반복되거나 보복 우려가 있으면 117 상담으로 학교 조치 절차를 확인하세요."
            );
        }
        return List.of(
                "보호자나 담임에게 상황과 증거를 공유하세요.",
                "증거가 정리되면 리포트를 생성해 상담 내용을 문서화하세요."
        );
    }

    private static String firstEvidenceAction(List<String> evidenceGuide) {
        if (evidenceGuide == null || evidenceGuide.isEmpty()) return "남아 있는 증거를 원본 형태로 먼저 보관하세요.";
        return evidenceGuide.get(0) + "부터 먼저 보관하세요.";
    }

    private static String nextConfirmationQuestionPreview(ReportReadiness readiness, List<Message> history) {
        long userMessages = history == null ? 0 : history.stream().filter(message -> "user".equals(message.getRole())).count();
        List<String> questions = previewConfirmationQuestions(readiness, combinedUserText(history), userMessages);
        return questions.isEmpty() ? "없음" : questions.get(0);
    }

    private static boolean hasAny(String text, String... words) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (String word : words) {
            if (normalized.contains(word.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) return value;
        }
        return null;
    }

    private static String findLawForLine(String line, Set<String> allowedLaws) {
        for (String law : allowedLaws) {
            if (line.contains(law) || lawAliases(law).stream().anyMatch(line::contains)) {
                return law;
            }
        }
        return null;
    }

    private static boolean isSupportedAlias(String marker, String law) {
        return lawAliases(law).contains(marker);
    }

    private static Set<String> lawAliases(String law) {
        Set<String> aliases = new HashSet<>();
        if (law.equals("학교폭력 예방 및 대책에 관한 법률")) aliases.add("학교폭력예방법");
        if (law.equals("성폭력범죄의 처벌 등에 관한 특례법")) aliases.add("성폭력처벌법");
        if (law.equals("아동·청소년의 성보호에 관한 법률")) aliases.add("아청법");
        if (law.equals("초·중등교육법")) aliases.add("초중등교육법");
        if (law.equals("스토킹범죄의 처벌 등에 관한 법률")) aliases.add("스토킹처벌법");
        return aliases;
    }

    private static boolean usesAllowedCharacters(String reply) {
        for (int offset = 0; offset < reply.length();) {
            int codePoint = reply.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (!Character.isLetter(codePoint)) continue;
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            if (script != Character.UnicodeScript.HANGUL
                    && script != Character.UnicodeScript.HAN
                    && script != Character.UnicodeScript.LATIN) {
                return false;
            }
            if (script == Character.UnicodeScript.LATIN && codePoint > 127) return false;
        }

        return true;
    }

    private List<Message> recentHistory(List<Message> history, int max) {
        if (history.size() <= max) return history;
        return history.subList(history.size() - max, history.size());
    }

    private String extractOpenAiStyleResponse(Map body) {
        if (body == null) throw new IllegalStateException("AI 응답이 비어 있습니다.");
        List choices = (List) body.getOrDefault("choices", List.of());
        if (choices.isEmpty()) throw new IllegalStateException("AI 선택지가 없습니다.");
        Map choice = (Map) choices.get(0);
        Map message = (Map) choice.get("message");
        return requireText(message.get("content"));
    }

    private String extractGeminiResponse(Map body) {
        if (body == null) throw new IllegalStateException("Gemini 응답이 비어 있습니다.");
        List candidates = (List) body.getOrDefault("candidates", List.of());
        if (candidates.isEmpty()) throw new IllegalStateException("Gemini 응답 후보가 없습니다.");
        Map content = (Map) ((Map) candidates.get(0)).get("content");
        if (content == null) throw new IllegalStateException("Gemini content가 없습니다.");
        List parts = (List) content.getOrDefault("parts", List.of());
        if (parts.isEmpty()) throw new IllegalStateException("Gemini text가 없습니다.");
        return requireText(((Map) parts.get(0)).get("text"));
    }

    private String requireText(Object value) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.isBlank() || "null".equalsIgnoreCase(text)) {
            throw new IllegalStateException("AI 응답 본문이 비어 있습니다.");
        }
        return text;
    }

    private long cooldownFor(Exception error) {
        if (error instanceof RestClientResponseException response
                && response.getStatusCode().value() == 402) {
            return 30 * 60_000L;
        }
        if (error instanceof RestClientResponseException response
                && response.getStatusCode().value() == 429) {
            return 30_000L;
        }
        if (error instanceof RestClientResponseException) {
            return 60_000L;
        }
        return 5_000L;
    }

    private void logProviderFailure(String provider, Exception error) {
        String reason = error.getClass().getSimpleName();
        if (error instanceof RestClientResponseException response) {
            reason += " HTTP " + response.getStatusCode().value();
        } else if (error.getMessage() != null && !error.getMessage().isBlank()) {
            reason += ": " + compactLogMessage(error.getMessage());
        }
        lastProviderFailure = provider.toLowerCase(Locale.ROOT);
        lastProviderFailureReason = reason;
        System.err.println("[AI] " + provider + " 호출 실패, 다음 공급자로 전환 (" + reason + ")");
    }

    private static String compactLogMessage(String message) {
        String compacted = message.replaceAll("\\s+", " ").trim();
        return compacted.length() <= 120 ? compacted : compacted.substring(0, 120) + "...";
    }

    public Map<String, Object> getProviderStatus() {
        return Map.of(
                "groq", groqApiKey != null && !groqApiKey.isBlank(),
                "gemini", geminiApiKey != null && !geminiApiKey.isBlank(),
                "deepseek", !effectiveDeepSeekApiKey().isBlank(),
                "deepseek_model", cheapestDeepSeekModel(deepSeekModel),
                "deepseek_max_tokens", effectiveDeepSeekMaxTokens(deepSeekMaxTokens),
                "claude", isClaudeBackupAvailable(),
                "last_provider", lastProvider,
                "last_failure_provider", lastProviderFailure,
                "last_failure_reason", lastProviderFailureReason
        );
    }

    private List<String> detectTypes(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        List<String> types = new ArrayList<>();
        if (hasPhysicalViolenceSignal(t) || containsAny(t, "폭행", "병원")) types.add("신체 폭력");
        if (containsAny(t, "욕", "협박", "모욕", "놀림", "비하") || hasDemeaningSpeechSignal(t)) types.add("언어 폭력");
        if (containsAny(t, "sns", "카톡", "단톡", "dm", "게시", "댓글", "사진")) types.add("사이버 폭력");
        if (containsAny(t, "따돌", "왕따", "무시", "소외")) types.add("따돌림");
        if (hasSexualViolationSignal(t)) types.add("성폭력");
        if (containsAny(t, "돈", "빼앗", "갈취", "강요")) types.add("갈취");
        return types;
    }

    private static boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) return true;
        }
        return false;
    }
}
