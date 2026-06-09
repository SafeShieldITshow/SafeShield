package com.safeshield.service;

import com.safeshield.model.Message;
import com.safeshield.model.Session;
import com.safeshield.model.User;
import com.safeshield.dto.AnalysisResult;
import com.safeshield.dto.MessageRequest.HistoryMessage;
import com.safeshield.dto.ReportReadiness;
import com.safeshield.repository.MessageRepository;
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

    private record PromptContext(String prompt, String lawContext, ReplyMode mode) {}
    private record ConfirmationCandidate(String id, String question, List<Map<String, String>> options) {}

    private static final Pattern LAW_HEADER_PATTERN =
            Pattern.compile("^===\\s*(.+?)\\s*===$");
    private static final Pattern ARTICLE_REFERENCE_PATTERN = Pattern.compile(
            "제\\s*(\\d+)\\s*조\\s*의\\s*(\\d+)"
                    + "|제\\s*(\\d+)\\s*의\\s*(\\d+)\\s*조"
                    + "|제\\s*(\\d+)\\s*조"
    );
    private static final Pattern ASCII_WORD_PATTERN = Pattern.compile("[A-Za-z]+");
    private static final Set<String> ALLOWED_ASCII_WORDS = Set.of(
            "AI", "SNS", "URL", "DM", "CCTV", "PDF", "ID", "IP",
            "JPG", "JPEG", "PNG", "S", "SHIELD"
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
    private final LawApiService lawApiService;
    private final AnalysisService analysisService;

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

    public ChatService(SessionRepository sessionRepository, MessageRepository messageRepository,
                       LawApiService lawApiService, AnalysisService analysisService) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.lawApiService = lawApiService;
        this.analysisService = analysisService;
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

        Message userMessage = new Message();
        userMessage.setSession(session);
        userMessage.setRole("user");
        userMessage.setContent(normalized);
        messageRepository.save(userMessage);

        List<Message> history = messageRepository.findBySessionOrderByCreatedAtAsc(session);
        ReportReadiness readiness = assessReadiness(history);
        List<Map<String, Object>> confirmationPrompts = confirmationPrompts(readiness, history);
        String reply = connectReplyToConfirmation(getAiReply(history), confirmationPrompts);

        Message aiMessage = new Message();
        aiMessage.setSession(session);
        aiMessage.setRole("assistant");
        aiMessage.setContent(reply);
        messageRepository.save(aiMessage);

        long userMessageCount = messageRepository.countBySessionAndRole(session, "user");
        return Map.of(
                "session_id", session.getId(),
                "reply", reply,
                "user_message_count", userMessageCount,
                "new_session_started", newSessionStarted,
                "report_ready", readiness.ready(),
                "report_status", readiness.status(),
                "report_reason", readiness.reason(),
                "missing_info", readiness.missingInfo(),
                "confirmation_prompts", confirmationPrompts
        );
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
        List<Map<String, Object>> confirmationPrompts = confirmationPrompts(readiness, history);
        String reply = connectReplyToConfirmation(getAiReply(history), confirmationPrompts);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("session_id", null);
        response.put("reply", reply);
        response.put("user_message_count", userMessageCount(history));
        response.put("new_session_started", false);
        response.put("report_ready", readiness.ready());
        response.put("report_status", readiness.status());
        response.put("report_reason", readiness.reason());
        response.put("missing_info", readiness.missingInfo());
        response.put("confirmation_prompts", confirmationPrompts);
        response.put("temporary", true);
        return response;
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

    private static Map<String, Object> messageToMap(Message message) {
        return Map.of(
                "id", message.getId(),
                "role", message.getRole(),
                "content", message.getContent(),
                "created_at", message.getCreatedAt().toString()
        );
    }

    private Map<String, Object> readinessToMap(ReportReadiness readiness, List<Message> history) {
        return Map.of(
                "ready", readiness.ready(),
                "status", readiness.status(),
                "reason", readiness.reason(),
                "missing_info", readiness.missingInfo(),
                "confirmation_prompts", confirmationPrompts(readiness, history),
                "school_violence_likely", readiness.schoolViolenceLikely()
        );
    }

    private List<Map<String, Object>> confirmationPrompts(ReportReadiness readiness, List<Message> history) {
        if (readiness.ready() || readiness.missingInfo().isEmpty()) return List.of();
        String combined = combinedUserText(history);
        return confirmationCandidates(readiness, combined, userMessageCount(history)).stream()
                .limit(1)
                .map(ChatService::confirmationPrompt)
                .toList();
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

    private static String connectReplyToConfirmation(String reply, List<Map<String, Object>> prompts) {
        if (reply == null || prompts == null || prompts.isEmpty()) return reply;
        String trimmed = stripGeneratedQuestionSentences(reply).trim();
        if (trimmed.isBlank()) {
            trimmed = "말해준 내용은 상담 기록에 반영했습니다.";
        }
        if (trimmed.contains("아래 확인 카드") || trimmed.contains("아래 선택지")) return trimmed;

        return (trimmed + "\n\n"
                + "방금 답변을 반영했습니다. 이어서 필요한 확인만 하나 더 할게요.").trim();
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

    private static String stripQuestionFragments(String line) {
        String result = line == null ? "" : line;
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
        if (id.startsWith("relationship")) {
            return "학교폭력 절차 적용 가능성을 보려면 상대와의 학교 관계가 필요합니다.";
        }
        if (id.startsWith("timeline")) {
            return "반복성, 지속성, 지금도 계속되는지에 따라 대응 우선순위가 달라져서 묻는 것입니다.";
        }
        if (id.startsWith("evidence") || id.startsWith("post_trace")) {
            return "리포트 신뢰도를 높이려면 지금 남아 있는 자료가 무엇인지 확인해야 합니다.";
        }
        if (id.startsWith("impact") || id.startsWith("physical_injury") || id.startsWith("physical_support")) {
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
        return "해당하는 항목은 여러 개 선택할 수 있고, 선택지에 없으면 직접 입력으로 적어주세요.";
    }

    static List<String> previewConfirmationQuestions(ReportReadiness readiness, String combinedText, long userMessageCount) {
        return confirmationCandidates(readiness, combinedText, userMessageCount).stream()
                .map(ConfirmationCandidate::question)
                .toList();
    }

    private static List<ConfirmationCandidate> confirmationCandidates(ReportReadiness readiness, String combinedText, long userMessageCount) {
        if (readiness == null || readiness.ready()) return List.of();
        String text = combinedText == null ? "" : combinedText;
        List<ConfirmationCandidate> candidates = new ArrayList<>();

        for (String missingInfo : readiness.missingInfo()) {
            if (missingInfo.contains("무슨 일")) candidates.add(incidentQuestion(text));
            else if (missingInfo.contains("학교 관계")) candidates.add(relationshipQuestion(text));
            else if (missingInfo.contains("언제")) candidates.add(timelineQuestion(text));
            else if (missingInfo.contains("증거")) candidates.add(evidenceQuestion(text));
            else if (missingInfo.contains("피해 영향") || missingInfo.contains("회복")) candidates.add(impactQuestion(text));
            else if (missingInfo.contains("원하는 도움")) candidates.add(goalQuestion(text));
            else if (missingInfo.contains("최종 확인")) candidates.add(finalCheckQuestion());
            else if (missingInfo.contains("조금 더")) candidates.addAll(deepDiveQuestions(text, userMessageCount));
        }

        if (candidates.isEmpty()) {
            candidates.add(genericDetailQuestion(text));
        }
        return candidates.stream().distinct().toList();
    }

    private static ConfirmationCandidate incidentQuestion(String text) {
        if (hasAny(text, "sns", "인스타", "게시", "댓글", "사진", "영상")) {
            return candidate("incident_post", "온라인에 올라온 내용이 정확히 무엇인가요? 사진, 글, 댓글, 공유 중 해당하는 것을 골라주세요.",
                    option("사진·영상", "확인 답변: 사진이나 영상이 올라왔습니다."),
                    option("비방 글", "확인 답변: 비방 글이 올라왔습니다."),
                    option("댓글 조롱", "확인 답변: 댓글로 조롱이나 비방이 있었습니다."),
                    option("공유·유포", "확인 답변: 다른 사람에게 공유되거나 퍼졌습니다."),
                    option("직접 입력", "확인 답변: "));
        }
        if (hasAny(text, "멍", "상처", "맞", "때", "밀")) {
            return candidate("incident_physical", "몸에 어떤 일이 있었나요? 맞음, 밀침, 넘어짐, 상처 중 가까운 것을 골라주세요.",
                    option("맞음", "확인 답변: 맞거나 가격당한 일이 있었습니다."),
                    option("밀침", "확인 답변: 밀치거나 넘어뜨리는 행동이 있었습니다."),
                    option("상처·멍", "확인 답변: 멍이나 상처가 생겼습니다."),
                    option("위협 동반", "확인 답변: 신체 행동과 함께 위협이 있었습니다."),
                    option("직접 입력", "확인 답변: "));
        }
        return candidate("incident", "실제로 있었던 행동을 조금 더 구체적으로 알려주세요. 말, 게시물, 신체 접촉, 따돌림 중 어디에 가까운가요?",
                option("욕설·비방", "확인 답변: 상대가 욕설이나 비방을 했습니다."),
                option("사진·게시물", "확인 답변: 사진이나 게시물이 올라왔습니다."),
                option("신체 폭력", "확인 답변: 때리거나 밀치는 신체 폭력이 있었습니다."),
                option("따돌림", "확인 답변: 따돌림이나 배제가 있었습니다."),
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

    private static ConfirmationCandidate evidenceQuestion(String text) {
        if (hasAny(text, "단톡", "단체 채팅", "채팅방", "카톡", "메시지")) {
            return candidate("evidence_chat", "대화 증거에는 무엇이 남아 있나요? 리포트에는 참여자, 시간, 앞뒤 맥락이 중요합니다.",
                    option("참여자+시간", "확인 답변: 참여자 목록과 보낸 시간이 보이는 캡처가 있습니다."),
                    option("앞뒤 맥락", "확인 답변: 앞뒤 대화 맥락이 보이는 캡처가 있습니다."),
                    option("일부 캡처만", "확인 답변: 일부 캡처만 있고 전체 맥락은 부족합니다."),
                    option("아직 없음", "확인 답변: 아직 확보한 증거는 없습니다."),
                    option("직접 입력", "확인 답변: "));
        }
        if (hasAny(text, "멍", "상처", "맞", "때", "밀", "병원", "진단")) {
            return candidate("evidence_physical", "신체 피해를 확인할 자료가 무엇인가요? 사진, 진료 기록, 목격자 중 해당하는 것을 골라주세요.",
                    option("상처 사진", "확인 답변: 멍이나 상처 사진이 있습니다."),
                    option("진료 기록", "확인 답변: 병원 진단서나 진료 기록이 있습니다."),
                    option("목격자", "확인 답변: 상황을 본 목격자가 있습니다."),
                    option("아직 없음", "확인 답변: 아직 확보한 증거는 없습니다."),
                    option("직접 입력", "확인 답변: "));
        }
        if (hasAny(text, "제가", "내가", "사과", "가해", "올렸", "욕했")) {
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
        if (hasAny(text, "제가", "내가", "사과", "가해", "올렸", "욕했")) {
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
        if (hasAny(text, "제가", "내가", "사과", "가해", "올렸", "욕했")) {
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
        return candidate("final_check", "지금까지 말한 내용이 하나의 같은 사안이고, 이 내용으로 리포트를 생성해도 되나요?",
                option("이 내용으로 분석", "확인 답변: 위 내용은 하나의 같은 사안이며 이 내용으로 리포트를 생성해도 됩니다."),
                option("추가 설명 필요", "확인 답변: "));
    }

    private static List<ConfirmationCandidate> deepDiveQuestions(String text, long userMessageCount) {
        List<ConfirmationCandidate> questions = new ArrayList<>();
        boolean actor = hasAny(text, "제가", "내가", "사과", "가해", "올렸", "욕했", "때렸");
        boolean groupChat = hasAny(text, "단톡", "단체 채팅", "채팅방");
        boolean post = hasAny(text, "sns", "인스타", "게시", "댓글", "유포", "온라인")
                || (hasAny(text, "사진", "영상") && hasAny(text, "올렸", "올라", "퍼졌", "공유", "단톡", "채팅방"));
        boolean physical = hasAny(text, "멍", "상처", "맞", "때렸", "밀쳤", "밀쳐", "병원", "진단");

        if (actor) {
            if (!hasAny(text, "중단", "그만", "삭제", "수정")) {
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

        if (groupChat) {
            if (!hasAny(text, "주도", "여러 명", "몇 명", "참여자", "강퇴", "초대 제외", "읽씹")) {
                questions.add(candidate("chat_pattern", "단톡방에서는 괴롭힘이 어떤 방식으로 반복됐나요? 여러 명이 같이 한 건지, 한 명이 주도한 건지, 배제도 있었는지 확인해야 합니다.",
                        option("여러 명이 같이", "확인 답변: 여러 명이 함께 조롱하거나 비방했습니다."),
                        option("한 명이 주도", "확인 답변: 한 명이 주도하고 다른 친구들이 반응했습니다."),
                        option("초대 제외·강퇴", "확인 답변: 초대 제외나 강퇴 같은 배제가 있었습니다."),
                        option("읽씹·무시", "확인 답변: 단체로 무시하거나 읽씹하는 일이 있었습니다."),
                        option("직접 입력", "확인 답변: ")));
            }
            if (!hasAny(text, "담임", "선생", "보호자", "부모", "117", "신고")) {
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
            if (!hasAny(text, "URL", "링크", "작성자", "게시 시간", "게시글 번호", "계정")) {
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
        }

        if (questions.isEmpty()) {
            questions.add(genericDetailQuestion(text));
        }
        return questions;
    }

    private static ConfirmationCandidate genericDetailQuestion(String text) {
        return candidate("more_context", "리포트를 더 정확하게 만들기 위해 하나만 더 확인할게요. 지금 가장 걱정되는 부분은 무엇인가요?",
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
        Map<Long, String> previews = messageRepository.findLatestUserMessagesBySessionIds(sessionIds).stream()
                .collect(Collectors.toMap(
                        message -> message.getSession().getId(),
                        message -> truncatePreview(message.getContent()),
                        (first, second) -> second
                ));

        return sessions.stream()
                .map(session -> {
                    return Map.<String, Object>of(
                            "session_id", session.getId(),
                            "preview", previews.getOrDefault(session.getId(), "새 상담"),
                            "message_count", messageCounts.getOrDefault(session.getId(), 0L),
                            "created_at", session.getCreatedAt().toString()
                    );
                })
                .toList();
    }

    private static String truncatePreview(String content) {
        String preview = content == null || content.isBlank() ? "새 상담" : content.trim();
        if (preview.length() > 44) {
            return preview.substring(0, 44) + "...";
        }
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

        int from = Math.max(0, items.size() - 12);
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

    private String getAiReply(List<Message> history) {
        String latestUserMessage = latestUserMessage(history);
        if (isIrrelevantInput(latestUserMessage) && !isConversationalFollowUp(latestUserMessage, history)) {
            lastProvider = "guardrail";
            if (isPerpetratorContext(combinedUserText(history))) {
                return buildPerpetratorOffTopicReply();
            }
            return buildIrrelevantInputReply();
        }

        PromptContext promptContext = buildPromptContext(history);
        Exception lastError = null;

        if (geminiApiKey != null && !geminiApiKey.isBlank()
                && System.currentTimeMillis() > geminiDisabledUntil) {
            try {
                String reply = requireValidGeneratedReply(
                        callGeminiApi(history, promptContext.prompt()),
                        promptContext
                );
                lastProvider = "gemini";
                return reply;
            } catch (Exception e) {
                lastError = e;
                geminiDisabledUntil = System.currentTimeMillis() + cooldownFor(e);
                logProviderFailure("Gemini", e);
            }
        }

        if (groqApiKey != null && !groqApiKey.isBlank() && System.currentTimeMillis() > groqDisabledUntil) {
            try {
                String reply = requireValidGeneratedReply(
                        callGroqApi(history, promptContext.prompt()),
                        promptContext
                );
                lastProvider = "groq";
                return reply;
            } catch (Exception e) {
                lastError = e;
                groqDisabledUntil = System.currentTimeMillis() + cooldownFor(e);
                logProviderFailure("Groq", e);
            }
        }

        if (!effectiveDeepSeekApiKey().isBlank() && System.currentTimeMillis() > deepSeekDisabledUntil) {
            try {
                String reply = requireValidGeneratedReply(
                        callDeepSeekApi(history, promptContext.prompt()),
                        promptContext
                );
                lastProvider = "deepseek";
                return reply;
            } catch (Exception e) {
                lastError = e;
                deepSeekDisabledUntil = System.currentTimeMillis() + cooldownFor(e);
                logProviderFailure("DeepSeek", e);
            }
        }

        if (isClaudeBackupAvailable()) {
            try {
                String reply = requireValidGeneratedReply(
                        callClaudeApi(history, promptContext.prompt()),
                        promptContext
                );
                lastProvider = "claude";
                return reply;
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
            return fallback;
        }

        lastProvider = "unavailable";
        throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "AI 상담 서비스에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요."
        );
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
        ReportReadiness readiness = analysisService.assessReportReadiness(combined, userMessageCount(history));
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
            reply.append("\n아래 확인 답변을 선택하거나 직접 입력하면 리포트 판단이 갱신됩니다.\n");
        } else {
            reply.append("\n리포트를 생성할 수 있습니다. 새 사안이 추가되면 별도 상담으로 나누는 것이 좋습니다.\n");
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
        ReportReadiness readiness = analysisService.assessReportReadiness(combined, userMessageCount(history));
        boolean perpetratorContext = isPerpetratorContext(combined);

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
                return "알겠습니다. 지금까지 확인된 내용은 상담 기록에 남아 있어서 리포트에도 반영됩니다.\n추가로 생각나는 변화가 있으면 이어서 말해 주세요.";
            }
            return "알겠습니다. 아직 리포트에는 몇 가지 확인이 더 필요합니다.\n생각나는 만큼만 이어서 말해 주면, 그 내용까지 상담 기록에 반영하겠습니다.";
        }

        if (containsAny(latest, "힘들", "무서", "불안", "짜증", "괴롭", "말하기", "모르겠", "걱정")) {
            return """
                    바로 분석부터 밀어붙이지 않고, 먼저 상황을 정리해 보겠습니다.
                    지금 말한 감정과 어려움도 상담 기록에 남고, 리포트에서는 피해 정도나 대응 필요성을 판단할 때 함께 반영됩니다.
                    당장 한 가지만 고르면 됩니다. 지금 가장 걱정되는 게 상대의 보복, 증거 부족, 학교에 말하는 것 중 어디에 가까운가요?
                    """.trim();
        }

        if (readiness.ready()) {
            return """
                    방금 말한 내용은 기존 상담에 추가로 반영됩니다.
                    리포트를 만들면 현재까지의 대화 전체를 기준으로 유형, 증거 상태, 권장 조치가 다시 계산됩니다.
                    더 이어서 말해도 되고, 새 사건이라면 새 상담으로 분리하는 게 좋습니다.
                    """.trim();
        }

        return """
                이 내용도 상담 기록에 반영해 두겠습니다.
                리포트를 정확히 만들려면 아직 사건 내용, 학교 관계, 시점, 증거 중 빠진 부분을 더 확인해야 합니다.
                편하게 이어서 말해 주세요. 길게 정리하지 않아도 됩니다.
                """.trim();
    }

    private String latestUserMessage(List<Message> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            Message message = history.get(i);
            if ("user".equals(message.getRole())) return message.getContent() == null ? "" : message.getContent().trim();
        }
        return "";
    }

    private boolean isIrrelevantInput(String text) {
        String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (t.isBlank()) return false;
        if (t.length() <= 1) return true;
        boolean hasConsultationSignal = containsAny(t,
                "학교", "같은 반", "반 친구", "친구", "선배", "후배", "학생", "담임", "선생", "학원",
                "때렸", "맞았", "폭행", "밀쳤", "멍", "상처", "욕", "모욕", "비방", "협박", "따돌", "왕따",
                "sns", "카톡", "단톡", "dm", "디엠", "게시", "댓글", "사진", "성추행", "성희롱", "갈취", "스토킹",
                "가해", "피해", "증거", "캡처", "신고", "117", "리포트", "상담");
        if (hasConsultationSignal) return false;
        if (isAcknowledgement(t) || isEmotionalConversation(t)) return false;

        return true;
    }

    private String buildIrrelevantInputReply() {
        return """
                학교폭력 상담과 직접 관련된 사건 내용이 아직 확인되지 않았습니다.

                이 채팅에서는 학교폭력, 사이버폭력, 따돌림, 협박, 폭행, 증거 정리처럼 상담에 필요한 내용만 분석할 수 있습니다.

                상담을 이어가려면 아래처럼 적어주세요.
                • 누가 어떤 행동을 했는지
                • 상대가 학교 관계자인지
                • 언제부터 몇 번 있었는지
                • 캡처, 사진, 진단서, 목격자 같은 증거가 있는지
                """.trim();
    }

    private String buildPerpetratorOffTopicReply() {
        return """
                지금 상담은 본인이 한 행동과 피해 회복을 기준으로 정리 중입니다.
                방금 내용은 이 사안과 직접 연결되는 정보로 보기 어렵습니다.
                같은 사안에 반영할 내용이면 삭제·사과·피해 회복·재발 방지 중 무엇과 관련되는지 말해 주세요. 다른 사안이면 새 상담으로 분리하는 편이 좋습니다.
                """.trim();
    }

    private boolean isConversationalFollowUp(String text, List<Message> history) {
        if (userMessageCount(history) <= 1) return false;
        return isAcknowledgement(text)
                || isEmotionalConversation(text)
                || containsAny(text, "어떡", "어떻게", "괜찮", "말해", "얘기", "상담", "리포트", "기록", "반영");
    }

    private boolean isAcknowledgement(String text) {
        return containsAny(text, "고마워", "감사", "알겠", "응", "네", "ㅇㅋ", "오케이", "맞아", "그래");
    }

    private boolean isEmotionalConversation(String text) {
        return containsAny(text, "힘들", "무서", "불안", "짜증", "괴롭", "말하기", "모르겠", "걱정", "답답", "억울");
    }

    private String callGroqApi(List<Message> history, String systemPrompt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(groqApiKey);

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        for (Message message : recentHistory(history, 10)) {
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
        for (Message message : recentHistory(history, 10)) {
            messages.add(Map.of(
                    "role", "assistant".equals(message.getRole()) ? "assistant" : "user",
                    "content", message.getContent()
            ));
        }

        String model = deepSeekModel == null || deepSeekModel.isBlank()
                ? "deepseek-v4-flash"
                : deepSeekModel.trim();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("max_tokens", Math.max(400, Math.min(deepSeekMaxTokens, 1200)));
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

    private String callGeminiApi(List<Message> history, String systemPrompt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-goog-api-key", geminiApiKey);

        List<Map<String, Object>> contents = new ArrayList<>();
        for (Message message : recentHistory(history, 10)) {
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
        for (Message message : recentHistory(history, 10)) {
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
        ReportReadiness readiness = analysisService.assessReportReadiness(combined, userMessages);
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
                6. 법령명과 조문 번호는 아래 '허용 인용 목록'에서 현재 상황과 직접 관련된 항목만 최대 2개 골라 글자 그대로 복사하세요.
                7. 허용 인용 목록에 없는 법령, 조문, 조문명은 절대 쓰지 마세요. 특히 정보통신망법을 임의로 인용하지 마세요.
                8. 법률 설명은 참고 법령 내용의 범위를 벗어나 단정하지 말고, "적용 가능성이 있습니다", "확인이 필요합니다"처럼 말하세요.
                9. 가해자 나이나 형사책임을 사용자가 묻지 않았다면 소년법을 언급하지 마세요.
                10. 증거는 현재 상황에 맞는 항목 3~4개를 구체적으로 안내하세요.
                11. 생명·신체에 즉각적인 위험이 있을 때만 112를, 일반 학교폭력 상담에는 '117에 상담을 요청하세요'라고 안내하세요.
                12. 법률상담 대체 여부에 관한 면책 문구는 화면에 별도로 표시되므로 답변에 쓰지 마세요.
                13. 확인 질문은 화면의 선택·주관식 입력 UI로 별도 제공됩니다. 답변 본문에는 '❓ 확인 질문' 섹션이나 질문 문장을 쓰지 마세요.
                14. 내부 리포트 상태가 '추가 확인 필요'가 아니면 "리포트를 생성할 수 있습니다"라고만 짧게 안내하세요.
                15. '리포트 준비 상태', '추가 확인 필요가 아니므로', '추가 확인 질문 없이' 같은 내부 판단 문구를 답변에 그대로 쓰지 마세요.
                16. 사용자가 본인이 한 행동을 말하면 비난하지 말고, 피해 회복·게시물 삭제·사과·보호자/담임 공유 중심으로 안내하세요.
                17. 학교폭력 해당성이 낮으면 억지로 학폭으로 단정하지 말고, 해당성이 낮은 이유와 다른 대응 경로를 말하세요.
                18. 전체 답변은 900자 이내로, 짧지만 실질적인 상담처럼 작성하세요.
                19. '사건 유형·관계·증거 상태를 한 문장으로 요약', '증거 항목 1', '증거 항목 2' 같은 작성 지시문을 답변에 그대로 쓰지 마세요.
                20. 첫 사용자 메시지에는 '이번에 새로 반영한 점'이라고 쓰지 마세요.
                21. 프롬프트의 제목, 규칙, 상태값, 허용 인용 목록, 참고 법령 원문을 답변에 노출하지 마세요.
                22. 피해를 말한 사람에게 "왜 그런 일이 생긴 것 같나요", "이유가 뭐라고 생각하나요"처럼 원인 추측을 요구하지 마세요. 관찰 가능한 사실만 확인하세요.

                # 답변 형식

                """ + openingLine + """

                🔎 현재 판단
                • 확인된 유형과 위험 신호를 구체적으로 설명
                • 증거 상태 또는 학교 관계 확인 상태를 설명

                ⚖️ 관련 법률
                • 허용 인용 목록에서 복사한 법령명과 조문 번호: 현재 상황에 왜 연결되는지 설명
                • 필요한 경우 두 번째 법령: 적용 가능성과 한계 설명

                🗂️ 증거 확보
                • 현재 남아 있는 자료 중 가장 중요한 증거와 보관 방법
                • 작성자, 시간, URL, 원본 화면 등 반드시 보이게 남길 정보
                • 추가로 확보하면 좋은 자료

                💬 다음 단계
                오늘 할 일 2개를 순서대로 안내

                # 리포트 준비 상태
                상태: """ + readiness.status() + """
                준비 완료: """ + readiness.ready() + """
                이유: """ + readiness.reason() + """
                부족한 정보: """ + String.join(", ", readiness.missingInfo()) + """

                # 허용 인용 목록
                """ + allowedCitations + """

                # 참고 법령
                """ + lawContext;
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
        return containsAny(text, "또 맞", "또 때", "때렸", "맞았", "출혈", "골절", "응급실", "흉기", "칼", "성추행", "성폭행", "자살", "자해", "죽고 싶", "보복", "협박");
    }

    private boolean hasCaseFactSignal(String text) {
        return containsAny(text,
                "학교", "같은 반", "반 친구", "친구", "선배", "후배", "학생", "담임", "선생", "학원",
                "때렸", "맞았", "폭행", "밀쳤", "멍", "상처", "욕", "모욕", "비방", "협박", "따돌", "왕따",
                "sns", "카톡", "단톡", "dm", "디엠", "게시", "댓글", "사진", "성추행", "성희롱", "갈취", "스토킹",
                "가해", "피해", "캡처", "url", "진단", "병원", "목격", "사과", "삭제", "보호자");
    }

    private boolean isPerpetratorContext(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        boolean directPhrase = containsAny(t,
                "제가 때렸", "내가 때렸", "제가 밀쳤", "내가 밀쳤",
                "제가 욕했", "내가 욕했", "제가 욕을 했", "내가 욕을 했",
                "제가 올렸", "내가 올렸", "제가 사진을 올렸", "내가 사진을 올렸",
                "제가 게시", "내가 게시", "제가 댓글", "내가 댓글",
                "제가 괴롭혔", "내가 괴롭혔", "제가 따돌", "내가 따돌",
                "저도 같이 욕", "같이 욕했", "장난으로 올렸",
                "사과하고 싶", "처벌받", "제가 가해", "내가 가해", "본인이 가해");
        boolean selfActor = containsAny(t, "제가", "내가", "저도", "본인이");
        boolean harmfulAction = containsAny(t,
                "때렸", "밀쳤", "욕했", "욕을 했", "욕설을 했", "올렸", "게시", "댓글을 달",
                "괴롭혔", "따돌", "놀렸", "빼앗", "강요");
        return directPhrase || (selfActor && harmfulAction);
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
                - 사용자가 피해나 두려움을 말하면 먼저 그 감정을 인정하고, 혼자 감당하지 않아도 된다는 메시지를 짧게 전하세요.
                - 보복, 협박, 접근 위험이 보이면 안전 확보와 보호자·담임·117 연결을 우선 안내하세요.
                """;

        return """
                당신은 학교폭력 상황을 듣는 따뜻한 상담자입니다. 지금 목표는 리포트를 빨리 만드는 것이 아니라,
                사용자가 안전하게 말할 수 있도록 돕고 리포트에 필요한 사실을 자연스럽게 확인하는 것입니다.

                # 답변 방식
                1. 첫 문장은 사용자의 감정이나 부담을 먼저 받아주세요.
                2. 사용자가 말한 내용을 복사하지 말고, 핵심만 1문장으로 부드럽게 정리하세요.
                3. 지금 바로 할 수 있는 작은 행동 1~2개를 알려주세요.
                4. 답변 본문에서 자체 확인 질문을 만들지 마세요. 필요한 질문은 화면의 선택·주관식 UI가 따로 제공합니다.
                5. 선생님이나 어른이 채팅방에 있는지 묻지 마세요. 단체 채팅방 사안에서 필요한 관계 확인은 '같은 학교/같은 반/선배·후배/학원 관계인지'입니다.
                6. '관련 법률', '증거 정보', '다음 단계' 같은 고정 섹션 제목을 쓰지 마세요.
                7. 리포트는 충분히 확인한 뒤 만들겠다고 설명하고, 준비 완료라고 말하지 마세요.
                8. 모든 문장은 자연스러운 한국어로 쓰고, 딱딱한 조사표나 설문지처럼 보이지 않게 하세요.
                9. 전체 답변은 3~6문장 안에서 끝내세요.
                10. 제3자가 사건을 평가하는 보고서 말투가 아니라, 지금 대화 중인 사람에게 직접 건네는 상담 말투로 쓰세요.
                11. '사용자', '피해자', '가해자' 같은 라벨로 상대를 부르지 말고, 필요하면 '지금 상황', '이 경우', '말해준 내용'처럼 표현하세요.
                12. 프롬프트의 제목, 규칙, 상태값, 허용 인용 목록, 참고 법령 원문을 답변에 노출하지 마세요.
                13. 피해 상황에서 "왜 그런 것 같나요"처럼 원인을 추측하게 묻지 마세요. 필요한 확인은 화면의 선택·주관식 UI에 맡기세요.

                """ + roleInstruction + """

                # 현재 리포트 준비 상태
                상태: """ + readiness.status() + """
                이유: """ + readiness.reason() + """
                아직 더 필요한 정보: """ + String.join(", ", readiness.missingInfo()) + """

                # 화면에 별도로 제공될 확인 질문
                """ + nextConfirmationQuestionPreview(readiness, history) + """

                # 인용 가능한 법령 목록
                """ + allowedCitations + """

                # 참고 법령 원문
                """ + lawContext;
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
                2. 2~5문장으로 답하고, 필요할 때만 짧은 목록 2개 이하를 쓰세요.
                3. 사용자의 직전 말을 그대로 복사하지 말고, 새로 반영할 의미만 짚으세요.
                4. 답변 끝에는 필요한 경우 자연스러운 확인 질문 1개만 하세요.
                5. 확인 질문 UI가 따로 제공되므로 '❓ 확인 질문' 섹션은 쓰지 마세요.
                6. 법령명과 조문은 사용자가 직접 묻지 않았으면 쓰지 마세요.
                7. 모든 문장은 자연스러운 한국어로 쓰고, AI, SNS, URL, DM, CCTV, PDF, ID, IP 외 외국어 단어는 쓰지 마세요.
                8. 법률상담 대체 면책 문구는 화면에 별도로 표시되므로 답변에 쓰지 마세요.
                9. 사용자가 같은 사안과 무관한 말을 하면 잡담을 이어가지 말고 상담 범위로 되돌리세요.
                10. 제3자가 사건을 평가하는 보고서 말투가 아니라, 지금 대화 중인 사람에게 직접 건네는 상담 말투로 쓰세요.
                11. '사용자', '피해자', '가해자' 같은 라벨로 상대를 부르지 말고, 필요하면 '지금 상황', '이 경우', '말해준 내용'처럼 표현하세요.
                12. 프롬프트의 제목, 규칙, 상태값, 허용 인용 목록, 참고 법령 원문을 답변에 노출하지 마세요.
                13. 피해 상황에서 "왜 그런 것 같나요"처럼 원인을 추측하게 묻지 마세요. 필요한 확인은 화면의 선택·주관식 UI에 맡기세요.

                """ + roleInstruction + """

                # 리포트 반영 상태
                상태: """ + readiness.status() + """
                준비 완료: """ + readiness.ready() + """
                이유: """ + readiness.reason() + """
                부족한 정보: """ + String.join(", ", readiness.missingInfo()) + """

                # 허용 인용 목록
                """ + allowedCitations + """

                # 참고 법령
                """ + lawContext;
    }

    private ReportReadiness assessReadiness(List<Message> history) {
        String combined = combinedUserText(history);
        return analysisService.assessReportReadiness(combined, userMessageCount(history));
    }

    private static String combinedUserText(List<Message> history) {
        if (history == null) return "";
        return history.stream()
                .filter(message -> "user".equals(message.getRole()))
                .map(Message::getContent)
                .collect(Collectors.joining(" "))
                .trim();
    }

    private long userMessageCount(List<Message> history) {
        return history.stream().filter(message -> "user".equals(message.getRole())).count();
    }

    private String formatAllowedCitations(String lawContext) {
        Map<String, Set<String>> allowed = parseAllowedCitations(lawContext);
        StringBuilder result = new StringBuilder();
        allowed.forEach((law, articles) -> articles.forEach(article ->
                result.append("- ").append(law).append(" 제").append(article).append("조\n")));
        return result.toString().trim();
    }

    private String requireValidGeneratedReply(String reply, PromptContext promptContext) {
        String sanitized = sanitizeGeneratedReply(reply);
        boolean valid = promptContext.mode() == ReplyMode.CONVERSATION
                ? isGeneratedConversationReplyValid(sanitized, promptContext.lawContext())
                : isGeneratedReplyValid(sanitized, promptContext.lawContext());
        if (!valid) {
            throw new IllegalStateException("AI 응답이 언어 또는 법령 검증을 통과하지 못했습니다.");
        }
        return sanitized;
    }

    static boolean isGeneratedConversationReplyValid(String reply, String lawContext) {
        if (reply == null || reply.isBlank() || reply.length() > 900) return false;
        if (containsForbiddenTemplatePhrase(reply)) return false;
        if (reply.contains("관련 법률")
                || reply.contains("증거 확보")
                || reply.contains("보관해야 할 증거")
                || reply.contains("다음 단계")
                || reply.contains("⚖️")
                || reply.contains("🗂️")) {
            return false;
        }
        if (!usesAllowedCharacters(reply)) return false;

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

    static boolean isGeneratedReplyValid(String reply, String lawContext) {
        if (reply == null || reply.isBlank() || reply.length() > 1800) return false;
        if (containsForbiddenTemplatePhrase(reply)) return false;
        if (!reply.contains("관련 법률")
                || !(reply.contains("보관해야 할 증거") || reply.contains("증거 확보"))
                || !reply.contains("다음 단계")) {
            return false;
        }
        if (!usesAllowedCharacters(reply)) return false;

        Map<String, Set<String>> allowed = parseAllowedCitations(lawContext);
        if (allowed.isEmpty()) return false;

        for (String marker : KNOWN_LAW_MARKERS) {
            if (reply.contains(marker) && allowed.keySet().stream().noneMatch(
                    law -> law.equals(marker) || isSupportedAlias(marker, law))) {
                return false;
            }
        }

        int citationCount = 0;
        for (String line : reply.split("\\R")) {
            List<String> references = extractArticleReferences(line);
            if (references.isEmpty()) continue;
            citationCount += references.size();

            String law = findLawForLine(line, allowed.keySet());
            if (law == null || !allowed.get(law).containsAll(references)) return false;
        }
        return citationCount >= 1 && citationCount <= 3;
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
                || reply.contains("선생님이나 어른이 계신가요")
                || reply.contains("채팅방에 참여하고 있는 친구들 중에 선생님")
                || (reply.contains("채팅방") && (reply.contains("어른") || reply.contains("선생님"))
                && (reply.contains("있") || reply.contains("계신")));
    }

    private static String sanitizeGeneratedReply(String reply) {
        if (reply == null) return "";
        return stripConfirmationQuestionSection(reply).lines()
                .filter(line -> !(line.contains("일반적인 법률 정보") && line.contains("대체하지")))
                .filter(line -> !(line.contains("리포트 준비 상태") && line.contains("추가 확인 필요")))
                .filter(line -> !line.contains("추가 확인 질문 없이"))
                .filter(line -> !line.contains("추가 확인 필요가 아니므로"))
                .map(line -> line
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

        Matcher words = ASCII_WORD_PATTERN.matcher(reply);
        while (words.find()) {
            if (!ALLOWED_ASCII_WORDS.contains(words.group().toUpperCase(Locale.ROOT))) return false;
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
                "claude", isClaudeBackupAvailable(),
                "last_provider", lastProvider
        );
    }

    private List<String> detectTypes(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        List<String> types = new ArrayList<>();
        if (containsAny(t, "맞", "때", "폭행", "멍", "상처", "병원")) types.add("신체 폭력");
        if (containsAny(t, "욕", "협박", "모욕", "놀림", "비하")) types.add("언어 폭력");
        if (containsAny(t, "sns", "카톡", "단톡", "dm", "게시", "댓글", "사진")) types.add("사이버 폭력");
        if (containsAny(t, "따돌", "왕따", "무시", "소외")) types.add("따돌림");
        if (containsAny(t, "성추행", "성희롱", "성적")) types.add("성폭력");
        if (containsAny(t, "돈", "빼앗", "갈취", "강요")) types.add("갈취");
        return types;
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) return true;
        }
        return false;
    }
}
