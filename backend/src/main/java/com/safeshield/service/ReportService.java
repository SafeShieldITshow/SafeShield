package com.safeshield.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safeshield.dto.AnalysisResult;
import com.safeshield.dto.ReportReadiness;
import com.safeshield.model.Message;
import com.safeshield.model.Report;
import com.safeshield.model.Session;
import com.safeshield.model.User;
import com.safeshield.repository.MessageRepository;
import com.safeshield.repository.ReportRepository;
import com.safeshield.repository.SessionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ReportService {

    public record ReportMutation(Map<String, Object> report, boolean generated, boolean updated) {}

    private final ReportRepository reportRepository;
    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final AnalysisService analysisService;
    private final ObjectMapper objectMapper;

    public ReportService(ReportRepository reportRepository, SessionRepository sessionRepository,
                         MessageRepository messageRepository, AnalysisService analysisService,
                         ObjectMapper objectMapper) {
        this.reportRepository = reportRepository;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.analysisService = analysisService;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> generate(User user, Long sessionId, String title) {
        if (sessionId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "리포트를 만들 상담 세션이 없습니다.");
        }

        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "상담 세션을 찾을 수 없습니다."));
        requireOwner(user, session);

        List<Message> messages = messageRepository.findBySessionOrderByCreatedAtAsc(session);
        List<Message> userMessages = messages.stream()
                .filter(m -> "user".equals(m.getRole()))
                .toList();
        if (userMessages.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "분석할 상담 내용이 없습니다.");
        }

        String userText = userMessages.stream()
                .map(Message::getContent)
                .reduce("", (a, b) -> a + " " + b)
                .trim();

        ReportReadiness readiness = assessEffectiveReadiness(userText, userMessages.size(), messages);
        return saveReport(user, session, userText, userMessages.size(), title, true, readiness);
    }

    public Map<String, Object> generateOrUpdateForSession(User user, Session session, String title) {
        return generateOrUpdateForSession(user, session, title, null);
    }

    public Map<String, Object> generateOrUpdateForSession(User user, Session session, String title, ReportReadiness suppliedReadiness) {
        return generateOrUpdateForSessionMutation(user, session, title, suppliedReadiness).report();
    }

    public ReportMutation generateOrUpdateForSessionMutation(User user, Session session, String title, ReportReadiness suppliedReadiness) {
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "리포트를 만들 상담 세션이 없습니다.");
        }
        requireOwner(user, session);

        List<Message> messages = messageRepository.findBySessionOrderByCreatedAtAsc(session);
        List<Message> userMessages = messages.stream()
                .filter(m -> "user".equals(m.getRole()))
                .toList();
        if (userMessages.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "분석할 상담 내용이 없습니다.");
        }

        String userText = userMessages.stream()
                .map(Message::getContent)
                .reduce("", (a, b) -> a + " " + b)
                .trim();

        ReportReadiness readiness = suppliedReadiness == null
                ? assessEffectiveReadiness(userText, userMessages.size(), messages)
                : suppliedReadiness;
        return saveReportMutation(user, session, userText, userMessages.size(), title, true, readiness);
    }

    public Map<String, Object> generateTemporary(List<Message> messages, String title) {
        return generateTemporary(messages, title, null);
    }

    public Map<String, Object> generateTemporary(List<Message> messages, String title, ReportReadiness suppliedReadiness) {
        List<Message> userMessages = messages == null ? List.of() : messages.stream()
                .filter(m -> "user".equals(m.getRole()))
                .toList();
        if (userMessages.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "분석할 상담 내용이 없습니다.");
        }

        String userText = userMessages.stream()
                .map(Message::getContent)
                .reduce("", (a, b) -> a + " " + b)
                .trim();

        ReportReadiness readiness = suppliedReadiness == null
                ? assessEffectiveReadiness(userText, userMessages.size(), messages)
                : suppliedReadiness;
        if (!readiness.ready()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    readiness.reason() + " 부족한 정보: " + String.join(", ", readiness.missingInfo())
            );
        }

        AnalysisResult analysis = analysisService.analyze(userText, readiness);
        Report report = new Report();
        applyAnalysis(report, analysis, title);
        Map<String, Object> map = toMap(report);
        map.put("temporary", true);
        return map;
    }

    private Map<String, Object> saveReport(User user, Session session, String userText, int userMessageCount, String title, boolean updateExisting) {
        return saveReport(user, session, userText, userMessageCount, title, updateExisting, null);
    }

    private Map<String, Object> saveReport(User user, Session session, String userText, int userMessageCount, String title, boolean updateExisting, ReportReadiness suppliedReadiness) {
        return saveReportMutation(user, session, userText, userMessageCount, title, updateExisting, suppliedReadiness).report();
    }

    private ReportMutation saveReportMutation(User user, Session session, String userText, int userMessageCount, String title, boolean updateExisting, ReportReadiness suppliedReadiness) {
        ReportReadiness readiness = suppliedReadiness == null
                ? analysisService.assessReportReadiness(userText, userMessageCount)
                : suppliedReadiness;
        if (!readiness.ready()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    readiness.reason() + " 부족한 정보: " + String.join(", ", readiness.missingInfo())
            );
        }

        AnalysisResult analysis = analysisService.analyze(userText, readiness);
        List<Report> reports = updateExisting ? reportRepository.findBySessionOrderByCreatedAtAsc(session) : List.of();
        boolean generated = reports.isEmpty();
        Report report = reports.isEmpty() ? new Report() : reports.get(reports.size() - 1);
        report.setUser(user);
        report.setSession(session);
        applyAnalysis(report, analysis, reports.isEmpty() ? title : generatedOrBlankTitle(report));

        reportRepository.save(report);
        return new ReportMutation(toMap(report), generated, !generated);
    }

    public boolean refreshReportsForSession(User user, Session session) {
        return refreshReportForSession(user, session, null) != null;
    }

    public Map<String, Object> refreshReportForSession(User user, Session session, ReportReadiness suppliedReadiness) {
        if (user == null || session == null) return null;
        requireOwner(user, session);

        List<Report> reports = reportRepository.findBySessionOrderByCreatedAtAsc(session);
        if (reports.isEmpty()) return null;

        List<Message> messages = messageRepository.findBySessionOrderByCreatedAtAsc(session);
        List<Message> userMessages = messages.stream()
                .filter(m -> "user".equals(m.getRole()))
                .toList();
        if (userMessages.isEmpty()) return null;

        String userText = userMessages.stream()
                .map(Message::getContent)
                .reduce("", (a, b) -> a + " " + b)
                .trim();
        ReportReadiness readiness = suppliedReadiness == null
                ? assessEffectiveReadiness(userText, userMessages.size(), messages)
                : suppliedReadiness;
        AnalysisResult analysis = analysisService.analyze(userText, readiness);

        for (Report report : reports) {
            applyAnalysis(report, analysis, generatedOrBlankTitle(report));
        }
        reportRepository.saveAll(reports);
        return toMap(reports.get(reports.size() - 1));
    }

    private ReportReadiness assessEffectiveReadiness(String userText, int userMessageCount, List<Message> messages) {
        ReportReadiness raw = analysisService.assessReportReadiness(userText, userMessageCount);
        return ChatService.applyHistoryAnswerState(raw, userText, userMessageCount, messages);
    }

    public List<Map<String, Object>> list(User user) {
        return reportRepository.findByUserOrderByCreatedAtDesc(user).stream()
                .map(this::toMap)
                .toList();
    }

    public Map<String, Object> latest(User user) {
        return reportRepository.findFirstByUserOrderByCreatedAtDesc(user)
                .map(this::toMap)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "아직 생성된 리포트가 없습니다."));
    }

    public Map<String, Object> get(User user, Long id) {
        Report report = reportRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "리포트를 찾을 수 없습니다."));
        if (!report.getUser().getId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "리포트 접근 권한이 없습니다.");
        }
        return toMap(report);
    }

    public void delete(User user, Long id) {
        Report report = reportRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "리포트를 찾을 수 없습니다."));
        if (!report.getUser().getId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "리포트 접근 권한이 없습니다.");
        }
        reportRepository.delete(report);
    }

    private String normalizeTitle(String title, AnalysisResult analysis) {
        if (title != null && !title.isBlank()) return title.trim();
        String primaryType = selectPrimaryType(analysis.violenceTypes());
        return analysis.assessmentStatus() + " - " + primaryType;
    }

    private String selectPrimaryType(List<String> violenceTypes) {
        if (violenceTypes == null || violenceTypes.isEmpty()) return "상담";
        for (String preferred : List.of("성폭력", "신체 폭력", "사이버 폭력", "갈취", "스토킹", "따돌림", "언어 폭력")) {
            if (violenceTypes.contains(preferred)) return preferred;
        }
        return violenceTypes.get(0);
    }

    private String generatedOrBlankTitle(Report report) {
        String title = report.getTitle();
        if (title == null || title.isBlank() || title.contains(" - ")) return "";
        return title;
    }

    private void applyAnalysis(Report report, AnalysisResult analysis, String title) {
        report.setTitle(normalizeTitle(title, analysis));
        report.setSummary(buildSummary(analysis));
        report.setRiskScore(analysis.riskScore());

        try {
            report.setViolenceTypes(objectMapper.writeValueAsString(analysis.violenceTypes()));
            report.setMatchedLaws(objectMapper.writeValueAsString(analysis.matchedLaws()));
            report.setLawRelevanceScores(objectMapper.writeValueAsString(analysis.lawRelevanceScores()));
            report.setExpectedMeasureRange(objectMapper.writeValueAsString(analysis.expectedMeasureRange()));
            report.setEvidenceGuide(objectMapper.writeValueAsString(analysis.evidenceGuide()));
            report.setAssessmentStatus(analysis.assessmentStatus());
            report.setAssessmentDetails(objectMapper.writeValueAsString(analysis.keyFindings()));
            report.setRecommendedActions(objectMapper.writeValueAsString(analysis.recommendedActions()));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "리포트 데이터를 저장하지 못했습니다.");
        }
    }

    private String buildSummary(AnalysisResult analysis) {
        String riskLabel = analysis.riskScore() >= 7 ? "높음" : analysis.riskScore() >= 4 ? "중간" : "낮음";
        String types = analysis.violenceTypes().isEmpty() ? "명확한 학교폭력 유형 없음" : String.join(", ", analysis.violenceTypes());
        String snapshot = findDetail(analysis, "핵심 상황:");
        String relationship = findDetail(analysis, "관계 판단:");
        String pattern = findDetail(analysis, "발생 양상:");
        String evidence = findDetail(analysis, "증거 수준:");
        String personalized = findDetail(analysis, "맞춤 판단:");
        String confidence = findDetail(analysis, "판단 신뢰도:");
        String actions = analysis.recommendedActions().stream().findFirst().orElse("상담 내용을 보완하세요.");
        String lead = snapshot.isBlank()
                ? "유형은 " + types + "로 정리됩니다."
                : snapshot;
        if (analysis.assessmentStatus().contains("가해")) {
            return analysis.assessmentStatus() + " 리포트입니다. "
                    + lead + " "
                    + "사안 중대도는 " + analysis.riskScore() + "/10 (" + riskLabel + ")로 산정했습니다. "
                    + compactSummaryParts(relationship, pattern, evidence, personalized, confidence) + " "
                    + "우선 조치: " + actions;
        }
        return analysis.assessmentStatus() + "입니다. "
                + lead + " "
                + "분류 유형은 " + types + "이고, 위험도는 " + analysis.riskScore() + "/10 (" + riskLabel + ")로 산정했습니다. "
                + compactSummaryParts(relationship, pattern, evidence, personalized, confidence) + " "
                + "우선 조치: " + actions;
    }

    private String compactSummaryParts(String... parts) {
        return String.join(" ", java.util.Arrays.stream(parts)
                .filter(part -> part != null && !part.isBlank())
                .toList());
    }

    private String findDetail(AnalysisResult analysis, String prefix) {
        return analysis.keyFindings().stream()
                .filter(item -> item.startsWith(prefix))
                .findFirst()
                .map(item -> item.substring(prefix.length()).trim())
                .orElse("");
    }

    private void requireOwner(User user, Session session) {
        if (session.getUser() == null || !session.getUser().getId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "상담 세션 접근 권한이 없습니다.");
        }
    }

    private Map<String, Object> toMap(Report report) {
        try {
            List<String> violenceTypes = read(report.getViolenceTypes(), new TypeReference<>() {}, List.of());
            List<String> matchedLaws = read(report.getMatchedLaws(), new TypeReference<>() {}, List.of());
            List<Double> lawScores = read(report.getLawRelevanceScores(), new TypeReference<>() {}, List.of());
            List<Integer> measureRange = read(report.getExpectedMeasureRange(), new TypeReference<>() {}, List.of(0, 3));
            List<String> evidenceGuide = read(report.getEvidenceGuide(), new TypeReference<>() {}, List.of());
            List<String> assessmentDetails = read(report.getAssessmentDetails(), new TypeReference<>() {}, List.of());
            List<String> recommendedActions = read(report.getRecommendedActions(), new TypeReference<>() {}, List.of());

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", report.getId());
            map.put("session_id", report.getSession() == null ? null : report.getSession().getId());
            map.put("title", report.getTitle());
            map.put("summary", report.getSummary());
            map.put("risk_score", report.getRiskScore());
            map.put("violence_types", violenceTypes);
            map.put("matched_laws", matchedLaws);
            map.put("law_relevance_scores", lawScores);
            map.put("expected_measure_range", measureRange);
            map.put("evidence_guide", evidenceGuide);
            map.put("assessment_status", report.getAssessmentStatus());
            map.put("assessment_details", assessmentDetails);
            map.put("recommended_actions", recommendedActions);
            map.put("created_at", report.getCreatedAt().toString());
            return map;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "리포트 데이터를 읽지 못했습니다.");
        }
    }

    private <T> T read(String json, TypeReference<T> type, T fallback) {
        if (json == null || json.isBlank()) return fallback;
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            return fallback;
        }
    }
}
