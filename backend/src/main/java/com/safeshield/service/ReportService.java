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

        ReportReadiness readiness = analysisService.assessReportReadiness(userText, userMessages.size());
        if (!readiness.ready()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    readiness.reason() + " 부족한 정보: " + String.join(", ", readiness.missingInfo())
            );
        }

        AnalysisResult analysis = analysisService.analyze(userText, readiness);
        String reportTitle = normalizeTitle(title, analysis);
        String summary = buildSummary(analysis);

        Report report = new Report();
        report.setUser(user);
        report.setSession(session);
        report.setTitle(reportTitle);
        report.setSummary(summary);
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

        reportRepository.save(report);
        return toMap(report);
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
        String primaryType = analysis.violenceTypes().isEmpty() ? "상담" : analysis.violenceTypes().get(0);
        return analysis.assessmentStatus() + " - " + primaryType;
    }

    private String buildSummary(AnalysisResult analysis) {
        String riskLabel = analysis.riskScore() >= 7 ? "높음" : analysis.riskScore() >= 4 ? "중간" : "낮음";
        String types = analysis.violenceTypes().isEmpty() ? "명확한 학교폭력 유형 없음" : String.join(", ", analysis.violenceTypes());
        String actions = analysis.recommendedActions().stream().limit(2).reduce((a, b) -> a + " " + b).orElse("상담 내용을 보완하세요.");
        return analysis.assessmentStatus() + "으로 정리됩니다. "
                + "확인된 유형은 " + types + "이며 위험도는 " + analysis.riskScore() + "/10 (" + riskLabel + ")입니다. "
                + actions;
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
