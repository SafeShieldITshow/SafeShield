package com.safeshield.service;

import com.safeshield.dto.AnalysisResult;
import com.safeshield.dto.ReportReadiness;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class AnalysisService {

    private final LawDataService lawDataService;
    private final EvidenceGuideService evidenceGuideService;

    public AnalysisService(LawDataService lawDataService, EvidenceGuideService evidenceGuideService) {
        this.lawDataService = lawDataService;
        this.evidenceGuideService = evidenceGuideService;
    }

    public AnalysisResult analyze(String userText) {
        String text = userText == null ? "" : userText;
        return analyzeWithReadiness(text, assessReportReadiness(text, estimateUserMessageCount(text)));
    }

    public AnalysisResult analyze(String userText, ReportReadiness readiness) {
        String text = userText == null ? "" : userText;
        return analyzeWithReadiness(text, readiness);
    }

    private AnalysisResult analyzeWithReadiness(String text, ReportReadiness readiness) {
        List<String> violenceTypes = detectViolenceTypes(text);
        double riskScore = calculateRiskScore(violenceTypes, text);
        if (!readiness.schoolViolenceLikely()) {
            riskScore = Math.min(riskScore, 4.5);
        }
        List<String> matchedLaws = violenceTypes.isEmpty() ? List.of() : lawDataService.getMatchedLaws(violenceTypes);
        List<Double> lawScores = violenceTypes.isEmpty() ? List.of() : lawDataService.getRelevanceScores(violenceTypes);
        if (matchedLaws.size() > 5) {
            matchedLaws = new ArrayList<>(matchedLaws.subList(0, 5));
            lawScores = new ArrayList<>(lawScores.subList(0, 5));
        }
        List<Integer> measureRange = detectMeasureRange(riskScore);
        List<String> evidenceGuide = evidenceGuideService.getEvidenceGuide(violenceTypes);
        List<String> findings = buildKeyFindings(text, violenceTypes, readiness);
        List<String> actions = buildRecommendedActions(readiness, violenceTypes);

        return new AnalysisResult(
                violenceTypes,
                riskScore,
                matchedLaws,
                lawScores,
                measureRange,
                evidenceGuide,
                readiness.status(),
                findings,
                actions
        );
    }

    public ReportReadiness assessReportReadiness(String text, long userMessageCount) {
        String t = normalize(text);
        List<String> missing = new ArrayList<>();
        List<String> facts = new ArrayList<>();

        boolean hasConcreteIncident = containsAny(t, "때렸", "맞았", "욕", "모욕", "비방", "사진", "게시", "댓글", "협박",
                "따돌", "돈", "갈취", "성희롱", "성추행", "괴롭", "놀림", "밀쳤", "사과", "제가", "내가");
        boolean hasActorContext = containsAny(t, "같은 반", "친구", "선배", "후배", "학생", "반 친구", "동급생", "가해자",
                "피해자", "담임", "선생", "학교", "학원");
        boolean hasTimeOrRepeat = containsAny(t, "오늘", "어제", "며칠", "몇 번", "계속", "반복", "매일", "한 번", "지난",
                "방금", "언제", "개월", "주일");
        boolean hasEvidenceOrChannel = containsAny(t, "캡처", "녹음", "사진", "진단", "병원", "목격", "sns", "카톡", "단톡",
                "댓글", "게시", "메시지", "증거");

        if (!hasConcreteIncident) missing.add("무슨 일이 있었는지");
        if (!hasActorContext) missing.add("상대가 학교 관계자인지");
        if (!hasTimeOrRepeat) missing.add("언제부터 몇 번 있었는지");
        if (!hasEvidenceOrChannel) missing.add("남아 있는 증거가 무엇인지");

        if (hasConcreteIncident) facts.add("구체적인 사건 내용 확인");
        if (hasActorContext) facts.add("상대방 또는 학교 관계 단서 확인");
        if (hasTimeOrRepeat) facts.add("시점 또는 반복성 단서 확인");
        if (hasEvidenceOrChannel) facts.add("증거 또는 발생 경로 단서 확인");

        String role = detectRole(t);
        boolean schoolContext = hasSchoolContext(t);
        boolean hasViolenceType = !detectViolenceTypes(t).isEmpty();
        boolean ready = userMessageCount >= 2 && missing.size() <= 1;
        boolean lowSchoolViolence = ready && (!schoolContext || !hasViolenceType);

        String status;
        String reason;
        if (!ready) {
            status = "추가 확인 필요";
            reason = "리포트 생성 전에 핵심 정보 " + Math.min(2, missing.size()) + "가지를 더 확인해야 합니다.";
        } else if (lowSchoolViolence) {
            status = "학교폭력 해당성 낮음";
            reason = "현재 정보만으로는 학교 관계 또는 학교폭력 유형과의 연결이 약합니다.";
        } else if ("가해 또는 연루".equals(role)) {
            status = "가해 또는 연루 가능성 검토";
            reason = "본인이 한 행동의 경위, 고의성, 피해 회복 여부를 중심으로 정리할 수 있습니다.";
        } else if ("목격자 또는 보호자".equals(role)) {
            status = "목격자 또는 보호자 관점";
            reason = "직접 피해 여부보다 신고·보호·증거 전달 절차 중심으로 정리할 수 있습니다.";
        } else {
            status = "학교폭력 가능성 검토";
            reason = "학교 관계와 폭력 유형 단서가 확인되어 리포트 생성이 가능합니다.";
        }

        return new ReportReadiness(ready, status, reason, missing.stream().limit(2).toList(), facts, !lowSchoolViolence);
    }

    private List<String> detectViolenceTypes(String text) {
        String t = normalize(text);
        List<String> types = new ArrayList<>();

        if (containsAny(t, "때렸", "맞았", "폭행", "밀쳤", "발로", "주먹", "상처", "멍", "상해", "다쳤")) {
            types.add("신체 폭력");
        }
        if (containsAny(t, "욕", "협박", "모욕", "비하", "놀림", "죽이", "꺼져", "소문", "명예훼손")) {
            types.add("언어 폭력");
        }
        if (containsAny(t, "sns", "카톡", "카카오", "단톡", "단체 채팅", "디엠", "dm", "인스타", "게시", "댓글", "사진 올", "온라인")) {
            types.add("사이버 폭력");
        }
        if (containsAny(t, "따돌", "왕따", "무시", "배제", "끼워주지", "혼자", "소외")) {
            types.add("따돌림");
        }
        if (containsAny(t, "성추행", "성희롱", "성적", "몸을 만", "강제로 만", "수치심")) {
            types.add("성폭력");
        }
        if (containsAny(t, "스토킹", "따라", "기다리", "집 앞", "계속 연락", "찾아와")) {
            types.add("스토킹");
        }
        if (containsAny(t, "돈", "갈취", "빼앗", "내놔", "물건", "강요", "심부름")) {
            types.add("갈취");
        }

        return types;
    }

    private double calculateRiskScore(List<String> violenceTypes, String text) {
        String t = text.toLowerCase(Locale.ROOT);
        double score = 1.4;

        if (violenceTypes.contains("언어 폭력")) score += 0.9;
        if (violenceTypes.contains("사이버 폭력")) score += 1.0;
        if (violenceTypes.contains("따돌림")) score += 1.1;
        if (violenceTypes.contains("갈취")) score += 1.5;
        if (violenceTypes.contains("스토킹")) score += 1.7;
        if (violenceTypes.contains("신체 폭력")) score += 2.1;
        if (violenceTypes.contains("성폭력")) score += 2.4;

        if (violenceTypes.size() > 1) score += Math.min(0.8, (violenceTypes.size() - 1) * 0.25);

        boolean repeated = containsAny(t, "계속", "매일", "반복", "몇 달", "여러 번", "지속", "몇 주", "몇 개월");
        boolean severeDistress = containsAny(t, "무서", "불안", "죽고", "자살", "학교 못", "등교 못", "잠을 못", "병원");
        boolean injury = containsAny(t, "출혈", "골절", "멍", "상처", "진단", "치료", "응급실");
        boolean directThreat = containsAny(t, "죽이", "때리겠", "찾아가", "가만 안", "협박");

        if (repeated) score += 0.8;
        if (severeDistress) score += 1.2;
        if (injury) score += 1.4;
        if (directThreat) score += 1.0;

        boolean severeSignal = severeDistress || injury || directThreat
                || violenceTypes.contains("신체 폭력")
                || violenceTypes.contains("성폭력")
                || violenceTypes.contains("스토킹")
                || violenceTypes.contains("갈취");
        boolean verbalOrCyberOnly = !violenceTypes.isEmpty()
                && violenceTypes.stream().allMatch(type -> type.equals("언어 폭력") || type.equals("사이버 폭력"));

        double cap = 10.0;
        if (verbalOrCyberOnly && !repeated && !severeSignal) {
            cap = 5.5;
        } else if (!severeSignal) {
            cap = 6.5;
        }

        return Math.round(Math.min(cap, score) * 10.0) / 10.0;
    }

    private List<Integer> detectMeasureRange(double risk) {
        if (risk >= 8.5) return List.of(6, 9);
        if (risk >= 7) return List.of(4, 7);
        if (risk >= 5) return List.of(2, 5);
        if (risk >= 3) return List.of(1, 3);
        return List.of(0, 2);
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) return true;
        }
        return false;
    }

    private String detectRole(String text) {
        if (containsAny(text, "제가 때렸", "내가 때렸", "제가 욕", "내가 욕", "제가 올렸", "내가 올렸",
                "제가 한", "내가 한", "가해", "사과하고", "처벌받")) {
            return "가해 또는 연루";
        }
        if (containsAny(text, "봤", "목격", "친구가 당", "자녀", "아이", "부모", "보호자")) {
            return "목격자 또는 보호자";
        }
        return "피해 또는 상담";
    }

    private boolean hasSchoolContext(String text) {
        return containsAny(text, "학교", "같은 반", "반 친구", "친구", "선배", "후배", "동급생", "학생", "담임", "선생", "학원");
    }

    private List<String> buildKeyFindings(String text, List<String> types, ReportReadiness readiness) {
        List<String> findings = new ArrayList<>();
        findings.add("판단 상태: " + readiness.status());
        findings.add(types.isEmpty() ? "명확한 학교폭력 유형은 아직 확인되지 않았습니다." : "확인된 유형: " + String.join(", ", types));
        findings.addAll(readiness.keyFacts());
        return findings.stream().distinct().limit(5).toList();
    }

    private List<String> buildRecommendedActions(ReportReadiness readiness, List<String> types) {
        if (!readiness.ready()) {
            return List.of("상대방과 학교 관계, 발생 시점, 증거 유무를 먼저 보완하세요.", "확인 질문에 답한 뒤 리포트를 생성하세요.");
        }
        if (!readiness.schoolViolenceLikely()) {
            return List.of("학교폭력 절차보다 일반 상담 또는 플랫폼 신고 가능성을 먼저 검토하세요.", "학교 관계가 추가로 확인되면 상담 내용을 보완하세요.");
        }
        if (readiness.status().contains("가해")) {
            return List.of("피해 회복을 위해 게시물 삭제, 사과, 재발 방지 의사를 정리하세요.", "보호자나 담임에게 먼저 알리고 사실관계를 숨기지 말고 설명하세요.");
        }
        return List.of("증거를 원본 형태로 보관하고 117 또는 학교 담당자에게 상담을 요청하세요.", "위험이 계속되면 보호자와 담임에게 즉시 공유하세요.");
    }

    private int estimateUserMessageCount(String text) {
        if (text == null || text.isBlank()) return 0;
        return Math.max(1, text.split("\\s+").length / 18);
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }
}
