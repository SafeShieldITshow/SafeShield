package com.safeshield.service;

import com.safeshield.model.Message;
import com.safeshield.model.Session;
import com.safeshield.model.User;
import com.safeshield.dto.AnalysisResult;
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

    private record PromptContext(String prompt, String lawContext) {}

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

    @Value("${gemini.api-key:}")
    private String geminiApiKey;

    @Value("${ai.backup-key:}")
    private String backupApiKey;

    private final RestTemplate aiClient;
    private volatile long groqDisabledUntil = 0;
    private volatile long geminiDisabledUntil = 0;
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
        String reply = getAiReply(history);

        Message aiMessage = new Message();
        aiMessage.setSession(session);
        aiMessage.setRole("assistant");
        aiMessage.setContent(reply);
        messageRepository.save(aiMessage);

        long userMessageCount = messageRepository.countBySessionAndRole(session, "user");
        ReportReadiness readiness = assessReadiness(history);
        return Map.of(
                "session_id", session.getId(),
                "reply", reply,
                "user_message_count", userMessageCount,
                "new_session_started", newSessionStarted,
                "report_ready", readiness.ready(),
                "report_status", readiness.status(),
                "report_reason", readiness.reason(),
                "missing_info", readiness.missingInfo()
        );
    }

    public List<Map<String, Object>> getMessages(Long sessionId, User user) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "상담 세션을 찾을 수 없습니다."));
        requireOwner(user, session);

        return messageRepository.findBySessionOrderByCreatedAtAsc(session).stream()
                .map(m -> Map.<String, Object>of(
                        "id", m.getId(),
                        "role", m.getRole(),
                        "content", m.getContent(),
                        "created_at", m.getCreatedAt().toString()
                ))
                .toList();
    }

    public Map<String, Object> getReadiness(Long sessionId, User user) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "상담 세션을 찾을 수 없습니다."));
        requireOwner(user, session);

        ReportReadiness readiness = assessReadiness(messageRepository.findBySessionOrderByCreatedAtAsc(session));
        return Map.of(
                "ready", readiness.ready(),
                "status", readiness.status(),
                "reason", readiness.reason(),
                "missing_info", readiness.missingInfo(),
                "school_violence_likely", readiness.schoolViolenceLikely()
        );
    }

    public List<Map<String, Object>> getSessions(User user) {
        return sessionRepository.findByUserOrderByCreatedAtDesc(user).stream()
                .map(session -> {
                    List<Message> messages = messageRepository.findBySessionOrderByCreatedAtAsc(session);
                    String preview = messages.stream()
                            .filter(m -> "user".equals(m.getRole()))
                            .reduce((first, second) -> second)
                            .map(Message::getContent)
                            .orElse("새 상담");
                    if (preview.length() > 44) {
                        preview = preview.substring(0, 44) + "...";
                    }
                    long userMessageCount = messages.stream()
                            .filter(m -> "user".equals(m.getRole()))
                            .count();
                    return Map.<String, Object>of(
                            "session_id", session.getId(),
                            "preview", preview,
                            "message_count", userMessageCount,
                            "created_at", session.getCreatedAt().toString()
                    );
                })
                .toList();
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
        PromptContext promptContext = buildPromptContext(history);
        Exception lastError = null;

        if (groqApiKey != null && !groqApiKey.isBlank() && System.currentTimeMillis() > groqDisabledUntil) {
            try {
                String reply = requireValidGeneratedReply(
                        callGroqApi(history, promptContext.prompt()),
                        promptContext.lawContext()
                );
                lastProvider = "groq";
                return reply;
            } catch (Exception e) {
                lastError = e;
                groqDisabledUntil = System.currentTimeMillis() + cooldownFor(e);
                logProviderFailure("Groq", e);
            }
        }

        if (geminiApiKey != null && !geminiApiKey.isBlank()
                && System.currentTimeMillis() > geminiDisabledUntil) {
            try {
                String reply = requireValidGeneratedReply(
                        callGeminiApi(history, promptContext.prompt()),
                        promptContext.lawContext()
                );
                lastProvider = "gemini";
                return reply;
            } catch (Exception e) {
                lastError = e;
                geminiDisabledUntil = System.currentTimeMillis() + cooldownFor(e);
                logProviderFailure("Gemini", e);
            }
        }

        if (backupApiKey != null && !backupApiKey.isBlank()) {
            try {
                String reply = requireValidGeneratedReply(
                        callClaudeApi(history, promptContext.prompt()),
                        promptContext.lawContext()
                );
                lastProvider = "claude";
                return reply;
            } catch (Exception e) {
                lastError = e;
                logProviderFailure("Claude", e);
            }
        }

        String fallback = buildFallbackReply(history, promptContext.lawContext());
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

    private String buildFallbackReply(List<Message> history, String lawContext) {
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
            reply.append("\n❓ 확인 질문\n");
            readiness.missingInfo().stream().limit(2)
                    .map(ChatService::toConfirmationQuestion)
                    .forEach(question -> reply.append("• ").append(question).append("\n"));
        } else {
            reply.append("\n리포트를 생성할 수 있습니다. 새 사안이 추가되면 별도 상담으로 나누는 것이 좋습니다.\n");
        }

        return reply.toString().trim();
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
        body.put("model", "llama-3.3-70b-versatile");
        body.put("messages", messages);
        body.put("max_completion_tokens", 1200);
        body.put("temperature", 0.1);

        ResponseEntity<Map> res = aiClient.exchange(
                "https://api.groq.com/openai/v1/chat/completions",
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

    private PromptContext buildPromptContext(List<Message> history) {
        String combined = history.stream()
                .filter(message -> "user".equals(message.getRole()))
                .map(Message::getContent)
                .collect(Collectors.joining(" "));
        List<String> violenceTypes = detectTypes(combined);
        ReportReadiness readiness = analysisService.assessReportReadiness(combined, userMessageCount(history));
        String lawContext = lawApiService.getContextForCase(combined, violenceTypes);
        if (lawContext == null || lawContext.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "법령 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요."
            );
        }
        if (lawContext.length() > 2600) lawContext = lawContext.substring(0, 2600);

        String allowedCitations = formatAllowedCitations(lawContext);

        String prompt = """
                당신은 학교폭력 피해 학생을 돕는 한국 법률 정보 상담 AI입니다.
                목표는 뻔한 위로가 아니라, 사용자가 "내 상황이 어떤 유형이고 지금 무엇을 해야 하는지" 바로 판단하도록 돕는 것입니다.

                # 반드시 지킬 규칙
                1. 파악된 사실을 먼저 정리하고, 부족한 정보는 추측하지 말고 질문하세요.
                2. 사용자 문장을 그대로 복사하거나 길게 다시 쓰지 말고, 사건 유형·관계·증거·확인 필요점으로 재구성하세요.
                3. 이전 답변과 같은 문장을 반복하지 말고, 이번 입력이 판단을 바꾼 점이나 새로 확인한 단서를 먼저 말하세요.
                4. 모든 문장은 자연스러운 한국어로 작성하세요. AI, SNS, URL, DM, CCTV, PDF, ID, IP 외 외국어 단어를 쓰지 마세요.
                5. 사용자를 '당신'이라고 부르지 말고 2인칭 표현을 생략하세요.
                6. 법령명과 조문 번호는 아래 '허용 인용 목록'에서 현재 상황과 직접 관련된 항목만 최대 2개 골라 글자 그대로 복사하세요.
                7. 허용 인용 목록에 없는 법령, 조문, 조문명은 절대 쓰지 마세요. 특히 정보통신망법을 임의로 인용하지 마세요.
                8. 법률 설명은 참고 법령 내용의 범위를 벗어나 단정하지 말고, "적용 가능성이 있습니다", "확인이 필요합니다"처럼 말하세요.
                9. 가해자 나이나 형사책임을 사용자가 묻지 않았다면 소년법을 언급하지 마세요.
                10. 증거는 현재 상황에 맞는 항목 3~4개를 구체적으로 안내하세요.
                11. 생명·신체에 즉각적인 위험이 있을 때만 112를, 일반 학교폭력 상담에는 '117에 상담을 요청하세요'라고 안내하세요.
                12. 법률상담 대체 여부에 관한 면책 문구는 화면에 별도로 표시되므로 답변에 쓰지 마세요.
                13. 아래 내부 리포트 상태가 '추가 확인 필요'일 때만 확인 질문을 최대 2개 넣으세요.
                14. 내부 리포트 상태가 '추가 확인 필요'가 아니면 확인 질문을 넣지 말고, "리포트를 생성할 수 있습니다"라고만 짧게 안내하세요.
                15. '리포트 준비 상태', '추가 확인 필요가 아니므로', '추가 확인 질문 없이' 같은 내부 판단 문구를 답변에 그대로 쓰지 마세요.
                16. 사용자가 본인이 한 행동을 말하면 비난하지 말고, 피해 회복·게시물 삭제·사과·보호자/담임 공유 중심으로 안내하세요.
                17. 학교폭력 해당성이 낮으면 억지로 학폭으로 단정하지 말고, 해당성이 낮은 이유와 다른 대응 경로를 말하세요.
                18. 전체 답변은 900자 이내로, 짧지만 실질적인 상담처럼 작성하세요.

                # 답변 형식

                이번에 새로 반영한 점: 새로 확인된 단서나 판단 변화 1줄

                사용자 문장을 그대로 붙여넣지 말고, 사건 유형·관계·증거 상태를 한 문장으로 요약

                🔎 현재 판단
                • 확인된 유형과 위험 신호를 구체적으로 설명
                • 증거 상태 또는 학교 관계 확인 상태를 설명

                ⚖️ 관련 법률
                • 허용 인용 목록에서 복사한 법령명과 조문 번호: 현재 상황에 왜 연결되는지 설명
                • 필요한 경우 두 번째 법령: 적용 가능성과 한계 설명

                🗂️ 증거 확보
                • 증거 항목 1: 어떻게 보관할지
                • 증거 항목 2: 무엇이 보이게 남길지
                • 증거 항목 3: 추가로 있으면 좋은 자료

                💬 다음 단계
                오늘 할 일 2개를 순서대로 안내

                ❓ 확인 질문
                추가 확인 필요일 때만 작성. 준비가 끝났으면 이 섹션을 생략.

                # 리포트 준비 상태
                상태: """ + readiness.status() + """
                준비 완료: """ + readiness.ready() + """
                이유: """ + readiness.reason() + """
                부족한 정보: """ + String.join(", ", readiness.missingInfo()) + """

                # 허용 인용 목록
                """ + allowedCitations + """

                # 참고 법령
                """ + lawContext;
        return new PromptContext(prompt, lawContext);
    }

    private ReportReadiness assessReadiness(List<Message> history) {
        String combined = history.stream()
                .filter(message -> "user".equals(message.getRole()))
                .map(Message::getContent)
                .collect(Collectors.joining(" "));
        return analysisService.assessReportReadiness(combined, userMessageCount(history));
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

    private String requireValidGeneratedReply(String reply, String lawContext) {
        String sanitized = sanitizeGeneratedReply(reply);
        if (!isGeneratedReplyValid(sanitized, lawContext)) {
            throw new IllegalStateException("AI 응답이 언어 또는 법령 검증을 통과하지 못했습니다.");
        }
        return sanitized;
    }

    static boolean isGeneratedReplyValid(String reply, String lawContext) {
        if (reply == null || reply.isBlank() || reply.length() > 1800) return false;
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

    private static String sanitizeGeneratedReply(String reply) {
        if (reply == null) return "";
        return reply.lines()
                .filter(line -> !(line.contains("일반적인 법률 정보") && line.contains("대체하지")))
                .filter(line -> !(line.contains("리포트 준비 상태") && line.contains("추가 확인 필요")))
                .filter(line -> !line.contains("추가 확인 질문 없이"))
                .filter(line -> !line.contains("추가 확인 필요가 아니므로"))
                .map(line -> line
                        .replace("117와", "117에")
                        .replace("117과", "117에")
                        .replace("당신의", "해당")
                        .replace("당신은 ", ""))
                .collect(Collectors.joining("\n"))
                .trim();
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

    private static String toConfirmationQuestion(String missingInfo) {
        if (missingInfo.contains("무슨 일")) return "상대가 한 행동을 한 문장으로 더 구체적으로 적어주세요.";
        if (missingInfo.contains("학교 관계")) return "상대가 같은 학교, 같은 반, 선배·후배, 학원 관계 중 어디에 해당하나요?";
        if (missingInfo.contains("언제")) return "언제부터 몇 번 있었고, 지금도 계속되고 있나요?";
        if (missingInfo.contains("증거")) return "캡처, URL, 사진, 진단서, 목격자 중 남아 있는 증거가 있나요?";
        return missingInfo + " 알려주세요.";
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
                "claude", backupApiKey != null && !backupApiKey.isBlank(),
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
        if (types.isEmpty()) types.add("언어 폭력");
        return types;
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) return true;
        }
        return false;
    }
}
