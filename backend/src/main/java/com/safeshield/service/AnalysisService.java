package com.safeshield.service;

import com.safeshield.dto.AnalysisResult;
import com.safeshield.dto.ReportReadiness;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class AnalysisService {

    private record CaseFacts(
            String role,
            String relationship,
            String incidentPattern,
            String evidenceLevel,
            String confidenceLevel,
            String confidenceReason
    ) {}

    private record MeasureFactors(
            int seriousness,
            int persistence,
            int intent,
            int mitigation,
            int aggravation,
            int total,
            boolean transferCandidate,
            boolean expelCandidate
    ) {}

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
        List<Integer> measureRange = detectMeasureRange(violenceTypes, text, readiness);
        List<String> evidenceGuide = evidenceGuideService.getEvidenceGuide(violenceTypes, text);
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
        boolean relevantInput = isRelevantConsultationInput(t);

        boolean hasConcreteIncident = containsAny(t, "때렸", "맞았", "욕", "모욕", "비방", "사진", "게시", "댓글", "협박",
                "따돌", "돈", "갈취", "성희롱", "성추행", "괴롭", "놀림", "밀쳤", "사과",
                "욕설", "신체 폭력", "사진이나 게시물", "때리거나 밀치는");
        boolean hasRelationshipAnswer = hasDefiniteSchoolRelationship(t) || hasConfirmedNonSchoolRelationship(t);
        boolean hasTimeOrRepeat = containsAny(t, "오늘", "어제", "며칠", "몇 번", "계속", "반복", "매일", "한 번", "지난",
                "방금", "언제", "개월", "주일", "현재까지는 한 번", "여러 번", "지금도 계속", "정확한 시점");
        boolean hasEvidenceOrChannel = containsAny(t, "캡처", "녹음", "사진", "진단", "병원", "목격", "sns", "카톡", "단톡",
                "댓글", "게시", "메시지", "증거", "아직 확보한 증거는 없습니다", "증거는 없습니다");

        if (!hasConcreteIncident) missing.add("무슨 일이 있었는지");
        if (!hasRelationshipAnswer) missing.add("상대가 학교 관계자인지");
        if (!hasTimeOrRepeat) missing.add("언제부터 몇 번 있었는지");
        if (!hasEvidenceOrChannel) missing.add("남아 있는 증거가 무엇인지");
        if (relevantInput && userMessageCount < 2 && missing.isEmpty()) {
            missing.add("사안 내용 최종 확인");
        }

        if (hasConcreteIncident) facts.add("구체적인 사건 내용 확인");
        if (hasRelationshipAnswer) facts.add("상대방과 학교 관계 확인");
        if (hasTimeOrRepeat) facts.add("시점 또는 반복성 단서 확인");
        if (hasEvidenceOrChannel) facts.add("증거 또는 발생 경로 단서 확인");

        String role = detectRole(t);
        boolean schoolContext = hasSchoolContext(t);
        boolean hasViolenceType = !detectViolenceTypes(t).isEmpty();
        boolean ready = relevantInput && userMessageCount >= 2 && missing.isEmpty();
        boolean lowSchoolViolence = ready && (!schoolContext || !hasViolenceType);

        String status;
        String reason;
        if (!relevantInput) {
            status = "상담 내용 확인 필요";
            reason = "학교폭력 상담과 직접 관련된 사건 내용이 아직 확인되지 않았습니다.";
        } else if (!ready) {
            status = "추가 확인 필요";
            reason = "리포트 생성 전에 사안 구조를 확정할 핵심 정보 " + Math.min(2, missing.size()) + "가지를 더 확인해야 합니다.";
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

        return new ReportReadiness(ready, status, reason, missing.stream().limit(2).toList(), facts, relevantInput && !lowSchoolViolence);
    }

    private boolean isRelevantConsultationInput(String text) {
        if (text.isBlank()) return false;
        return containsAny(text,
                "학교", "같은 반", "반 친구", "친구", "선배", "후배", "동급생", "학생", "담임", "선생", "학원",
                "때렸", "맞았", "폭행", "밀쳤", "멍", "상처", "욕", "모욕", "비방", "협박", "따돌", "왕따",
                "sns", "카톡", "단톡", "dm", "디엠", "게시", "댓글", "사진", "성추행", "성희롱", "갈취", "스토킹",
                "가해", "피해", "증거", "캡처", "신고", "117", "리포트", "상담");
    }

    private List<String> detectViolenceTypes(String text) {
        String t = normalize(text);
        List<String> types = new ArrayList<>();

        if (containsAny(t, "때렸", "맞았", "폭행", "밀쳤", "발로", "주먹", "상처", "멍", "상해", "다쳤")) {
            types.add("신체 폭력");
        }
        if (containsAny(t, "욕", "협박", "모욕", "비하", "비방", "놀림", "죽이", "꺼져", "소문", "명예훼손")) {
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
        double score = 1.2;

        if (violenceTypes.contains("언어 폭력")) score += 0.5;
        if (violenceTypes.contains("사이버 폭력")) score += 0.6;
        if (violenceTypes.contains("따돌림")) score += 0.8;
        if (violenceTypes.contains("갈취")) score += 1.0;
        if (violenceTypes.contains("스토킹")) score += 1.2;
        if (violenceTypes.contains("신체 폭력")) score += 1.3;
        if (violenceTypes.contains("성폭력")) score += 2.0;

        if (violenceTypes.size() > 1) score += Math.min(1.0, (violenceTypes.size() - 1) * 0.35);

        boolean oneOff = containsAny(t, "한 번", "1번", "처음", "처음으로");
        boolean repeated = containsAny(t, "계속", "매일", "반복", "여러 번", "지속", "몇 번", "또");
        boolean longTerm = containsAny(t, "몇 달", "몇 개월", "몇 주", "개월", "학기", "작년부터");
        boolean ongoing = containsAny(t, "아직도", "지금도", "계속하고", "멈추지", "또 올");
        boolean publicSpread = containsAny(t, "공개", "퍼졌", "유포", "공유", "단톡", "단체", "여러 명", "댓글", "게시");
        boolean identityExposure = containsAny(t, "사진", "영상", "얼굴", "신상", "이름", "학교명", "전화번호");
        boolean severeDistress = containsAny(t, "죽고", "자살", "자해", "극단");
        boolean distress = severeDistress || containsAny(t, "무서", "불안", "학교 못", "등교 못", "잠을 못", "상담", "병원");
        boolean directThreat = containsAny(t, "죽이", "때리겠", "찾아가", "가만 안", "협박");
        boolean weaponOrGroup = containsAny(t, "흉기", "칼", "위험한 물건", "여러 명", "단체로", "무리", "선배");
        boolean resolved = containsAny(t, "삭제", "사과", "멈췄", "그만뒀", "해결");

        if (repeated) score += 0.6;
        if (longTerm) score += 0.4;
        if (ongoing) score += 0.3;
        if (oneOff && !repeated && !ongoing) score -= 0.3;
        if (publicSpread) score += 0.4;
        if (identityExposure) score += 0.35;
        if (directThreat) score += 0.8;
        if (weaponOrGroup) score += 0.5;
        if (distress) score += 0.45;
        if (severeDistress) score += 0.8;
        if (resolved && !ongoing && !repeated) score -= 0.4;

        double injuryScore = 0.0;
        if (containsAny(t, "멍", "상처", "다쳤")) injuryScore = Math.max(injuryScore, 0.5);
        if (containsAny(t, "진단", "치료", "병원", "상해")) injuryScore = Math.max(injuryScore, 0.9);
        if (containsAny(t, "출혈", "골절", "응급실", "수술", "기절")) injuryScore = Math.max(injuryScore, 1.3);
        score += injuryScore;

        if (violenceTypes.contains("성폭력") && containsAny(t, "사진", "영상", "촬영", "유포", "온라인", "dm", "디엠")) {
            score += 0.8;
        }
        if (violenceTypes.contains("갈취") && containsAny(t, "돈", "송금", "계좌", "만원", "물건")) {
            score += 0.4;
        }

        if (!violenceTypes.isEmpty()) {
            MeasureFactors factors = assessMeasureFactors(violenceTypes, text);
            double factorBasedScore = 1.6
                    + factors.seriousness() * 1.2
                    + factors.persistence() * 0.65
                    + factors.intent() * 0.5
                    + factors.aggravation() * 0.55
                    - factors.mitigation() * 0.35;
            score = Math.max(score, factorBasedScore);
        }

        boolean severeSignal = severeDistress || injuryScore >= 1.1 || directThreat || weaponOrGroup
                || violenceTypes.contains("신체 폭력")
                || violenceTypes.contains("성폭력")
                || violenceTypes.contains("스토킹")
                || violenceTypes.contains("갈취");
        boolean verbalOrCyberOnly = !violenceTypes.isEmpty()
                && violenceTypes.stream().allMatch(type -> type.equals("언어 폭력") || type.equals("사이버 폭력"));

        double cap = 10.0;
        if (verbalOrCyberOnly && !repeated && !severeSignal) {
            cap = publicSpread || identityExposure ? 5.0 : 4.2;
        } else if (verbalOrCyberOnly && !severeSignal) {
            cap = 5.8;
        } else if (violenceTypes.contains("신체 폭력") && oneOff && injuryScore <= 0.5 && !directThreat && !weaponOrGroup) {
            cap = 5.3;
        } else if (!severeSignal) {
            cap = 5.8;
        }

        return Math.round(Math.max(1.0, Math.min(cap, score)) * 10.0) / 10.0;
    }

    private List<Integer> detectMeasureRange(List<String> types, String text, ReportReadiness readiness) {
        if (!readiness.schoolViolenceLikely()) {
            return List.of(0, 2);
        }
        MeasureFactors factors = assessMeasureFactors(types, text);
        int total = factors.total();

        int start;
        int end;
        if (total <= 3) {
            start = 0;
            end = 2;
        } else if (total <= 5) {
            start = 0;
            end = 3;
        } else if (total <= 7) {
            start = 1;
            end = 4;
        } else if (total <= 9) {
            start = 2;
            end = 5;
        } else if (total <= 11) {
            start = 3;
            end = 6;
        } else if (total <= 13) {
            start = 4;
            end = 7;
        } else {
            start = 5;
            end = 8;
        }

        String t = normalize(text);
        boolean verbalOrCyberOnly = !types.isEmpty()
                && types.stream().allMatch(type -> type.equals("언어 폭력") || type.equals("사이버 폭력"));
        boolean oneOff = containsAny(t, "한 번", "1번", "처음", "처음으로")
                && !containsAny(t, "계속", "반복", "여러 번", "매일", "몇 달", "몇 개월");

        if (!factors.expelCandidate()) {
            end = Math.min(end, 7);
        }
        if (!factors.transferCandidate()) {
            end = Math.min(end, 6);
        }
        if (verbalOrCyberOnly && oneOff) {
            end = Math.min(end, 4);
        }
        if (oneOff && factors.seriousness() <= 2 && factors.aggravation() == 0) {
            start = Math.min(start, 1);
            end = Math.min(end, 3);
        }
        return List.of(start, Math.max(start, end));
    }

    private MeasureFactors assessMeasureFactors(List<String> types, String text) {
        String t = normalize(text);
        int seriousness = 0;

        if (types.contains("언어 폭력")) seriousness = Math.max(seriousness, 1);
        if (types.contains("사이버 폭력")) {
            seriousness = Math.max(seriousness, containsAny(t, "사진", "영상", "신상", "얼굴", "유포", "공개", "게시") ? 2 : 1);
        }
        if (types.contains("따돌림")) seriousness = Math.max(seriousness, 2);
        if (types.contains("갈취")) seriousness = Math.max(seriousness, containsAny(t, "만원", "송금", "계좌", "여러 번") ? 3 : 2);
        if (types.contains("스토킹")) seriousness = Math.max(seriousness, 3);
        if (types.contains("신체 폭력")) {
            seriousness = Math.max(seriousness, 2);
            if (containsAny(t, "진단", "치료", "병원", "상해", "출혈")) seriousness = Math.max(seriousness, 3);
            if (containsAny(t, "골절", "응급실", "수술", "기절", "흉기", "칼")) seriousness = Math.max(seriousness, 4);
        }
        if (types.contains("성폭력")) seriousness = Math.max(seriousness, 4);
        if (containsAny(t, "자살", "자해", "죽고 싶", "극단")) seriousness = Math.min(4, seriousness + 1);

        int persistence = 0;
        if (containsAny(t, "한 번", "1번", "처음", "처음으로")) persistence = 0;
        if (containsAny(t, "몇 번", "여러 번", "반복", "매일", "또")) persistence = Math.max(persistence, 2);
        if (containsAny(t, "계속", "몇 주", "몇 달", "몇 개월", "학기", "작년부터", "지금도", "아직도")) persistence = Math.max(persistence, 3);
        if (containsAny(t, "몇 달 동안 계속", "몇 개월 동안 계속", "매일 계속")) persistence = 4;

        int intent = 1;
        if (!types.isEmpty()) intent = 2;
        if (containsAny(t, "협박", "죽이", "찾아가", "유포", "게시", "단톡", "단체", "여러 명", "돈", "강요")) {
            intent = Math.max(intent, 3);
        }
        if (containsAny(t, "보복", "신고하면", "흉기", "칼", "강제로", "촬영", "퍼뜨")) {
            intent = 4;
        }
        if (containsAny(t, "장난", "실수", "몰랐")) {
            intent = Math.max(1, intent - 1);
        }

        int mitigation = 0;
        if (containsAny(t, "삭제", "사과", "멈췄", "그만뒀", "반성", "돌려줬", "변상")) mitigation += 1;
        if (containsAny(t, "화해", "합의", "피해 회복", "상담받", "담임에게 말", "보호자에게 말")) mitigation += 1;
        mitigation = Math.min(2, mitigation);

        int aggravation = 0;
        if (containsAny(t, "보복", "신고하면", "협박", "때리겠", "찾아가", "장애", "특수학급", "여러 명", "단체로", "무리")) aggravation += 1;
        if (containsAny(t, "흉기", "칼", "촬영", "유포", "골절", "응급실", "수술")) aggravation += 1;
        if (containsAny(t, "반성 안", "사과 안", "계속하겠", "삭제 안")) aggravation += 1;
        aggravation = Math.min(3, aggravation);

        boolean transferCandidate = seriousness >= 4
                || aggravation >= 2
                || (seriousness >= 3 && persistence >= 3)
                || (containsAny(t, "보복", "신고하면") && persistence >= 2);
        boolean expelCandidate = containsAny(t, "흉기", "칼", "골절", "응급실", "수술", "강제추행", "성폭행")
                || (types.contains("성폭력") && containsAny(t, "촬영", "유포", "영상"))
                || (containsAny(t, "보복", "신고하면") && seriousness >= 4);

        int total = Math.max(0, seriousness + persistence + intent + aggravation - mitigation);
        return new MeasureFactors(seriousness, persistence, intent, mitigation, aggravation, total, transferCandidate, expelCandidate);
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) return true;
        }
        return false;
    }

    private String detectRole(String text) {
        if (isPerpetratorPerspective(text)) {
            return "가해 또는 연루";
        }
        if (containsAny(text, "봤", "목격", "친구가 당", "자녀", "아이", "부모", "보호자")) {
            return "목격자 또는 보호자";
        }
        return "피해 또는 상담";
    }

    private boolean isPerpetratorPerspective(String text) {
        boolean directPhrase = containsAny(text,
                "제가 때렸", "내가 때렸", "제가 밀쳤", "내가 밀쳤",
                "제가 욕했", "내가 욕했", "제가 욕을 했", "내가 욕을 했",
                "제가 올렸", "내가 올렸", "제가 사진을 올렸", "내가 사진을 올렸",
                "제가 게시", "내가 게시", "제가 댓글", "내가 댓글",
                "제가 괴롭혔", "내가 괴롭혔", "제가 따돌", "내가 따돌",
                "저도 같이 욕", "같이 욕했", "장난으로 올렸",
                "사과하고 싶", "처벌받", "제가 가해", "내가 가해", "본인이 가해");
        boolean selfActor = containsAny(text, "제가", "내가", "저도", "본인이");
        boolean harmfulAction = containsAny(text,
                "때렸", "밀쳤", "욕했", "욕을 했", "욕설을 했", "올렸", "게시", "댓글을 달",
                "괴롭혔", "따돌", "놀렸", "빼앗", "강요");
        return directPhrase || (selfActor && harmfulAction);
    }

    private boolean hasSchoolContext(String text) {
        return hasDefiniteSchoolRelationship(text);
    }

    private boolean hasDefiniteSchoolRelationship(String text) {
        return containsAny(text,
                "같은 반", "반 친구", "같은 학교", "학교 친구", "동급생", "선배", "후배", "학생",
                "담임", "선생", "학원", "상대는 같은 반", "상대는 같은 학교");
    }

    private boolean hasConfirmedNonSchoolRelationship(String text) {
        return containsAny(text, "학교 밖", "학교 관계자가 아닙", "학교 관계자는 아닙", "학교 관계 없음");
    }

    private List<String> buildKeyFindings(String text, List<String> types, ReportReadiness readiness) {
        CaseFacts facts = analyzeCaseFacts(text, types, readiness);
        List<String> findings = new ArrayList<>();
        findings.add("판단 상태: " + readiness.status());
        findings.add("사안 역할: " + facts.role());
        findings.add("관계 판단: " + facts.relationship());
        findings.add(types.isEmpty() ? "행위 유형: 명확한 학교폭력 유형은 아직 확인되지 않았습니다." : "행위 유형: " + String.join(", ", types));
        findings.add("발생 양상: " + facts.incidentPattern());
        findings.add("증거 수준: " + facts.evidenceLevel());
        findings.add("판단 신뢰도: " + facts.confidenceLevel() + " - " + facts.confidenceReason());
        findings.add("예상 조치 근거: " + buildMeasureBasis(text, types, readiness));
        return findings.stream().distinct().limit(8).toList();
    }

    private String buildMeasureBasis(String text, List<String> types, ReportReadiness readiness) {
        if (!readiness.schoolViolenceLikely()) {
            return "학교폭력 해당성이 낮아 1~3호 수준의 낮은 범위로 제한해 참고합니다.";
        }
        MeasureFactors factors = assessMeasureFactors(types, text);
        return "심각성 " + factors.seriousness()
                + ", 지속성 " + factors.persistence()
                + ", 고의성 " + factors.intent()
                + ", 감경 단서 " + factors.mitigation()
                + ", 가중 단서 " + factors.aggravation()
                + "를 함께 반영했습니다.";
    }

    private List<String> buildRecommendedActions(ReportReadiness readiness, List<String> types) {
        if (!readiness.ready()) {
            return List.of("상대방과 학교 관계, 발생 시점, 증거 유무를 먼저 보완하세요.", "확인 질문에 답한 뒤 리포트를 생성하세요.");
        }
        if (!readiness.schoolViolenceLikely()) {
            return List.of("학교폭력 절차보다 일반 상담 또는 플랫폼 신고 가능성을 먼저 검토하세요.", "학교 관계가 추가로 확인되면 상담 내용을 보완하세요.");
        }
        if (readiness.status().contains("가해")) {
            List<String> actions = new ArrayList<>();
            actions.add("추가 연락, 댓글, 게시, 주변 공유를 즉시 중단하고 보복이나 압박으로 보일 행동을 피하세요.");
            if (types.contains("사이버 폭력")) {
                actions.add("게시물·댓글·대화 원본과 삭제·수정 내역을 보관한 뒤, 학교나 보호자와 삭제 및 피해 회복 절차를 진행하세요.");
            }
            if (types.contains("신체 폭력")) {
                actions.add("상대방의 상해 여부와 치료 필요성을 확인하고, 보호자·담임에게 사실관계를 숨기지 말고 설명하세요.");
            }
            actions.add("사과는 직접 압박하지 말고 보호자나 학교를 통해 전달하고, 피해 회복 의사를 구체적으로 정리하세요.");
            actions.add("발생 경위, 본인이 한 행동, 중단한 조치, 재발 방지 계획을 시간순으로 작성하세요.");
            return actions.stream().distinct().limit(4).toList();
        }
        List<String> actions = new ArrayList<>();
        if (types.contains("신체 폭력")) {
            actions.add("상처 사진과 진료 기록을 먼저 확보하고, 통증이나 상해가 있으면 보호자와 병원 기록을 남기세요.");
        }
        if (types.contains("사이버 폭력")) {
            actions.add("게시물·댓글·계정·URL·게시 시간을 삭제 전 한 화면에 보이게 저장하세요.");
        }
        if (types.contains("갈취")) {
            actions.add("돈이나 물건 요구 메시지와 송금·결제 내역을 금액, 날짜, 상대 계정과 함께 정리하세요.");
        }
        if (types.contains("성폭력")) {
            actions.add("혼자 대응하지 말고 보호자, 학교 전담교사, 전문 상담기관과 즉시 공유하세요.");
        }
        actions.add("사안 구조와 증거를 정리한 뒤 담임 또는 학교폭력 담당자에게 상담을 요청하세요.");
        actions.add("반복되거나 보복 우려가 있으면 117 상담으로 보호 조치 절차를 확인하세요.");
        return actions.stream().distinct().limit(4).toList();
    }

    private CaseFacts analyzeCaseFacts(String text, List<String> types, ReportReadiness readiness) {
        String t = normalize(text);
        String role = detectRole(t);

        String relationship;
        if (hasDefiniteSchoolRelationship(t)) {
            relationship = "학교 또는 학원 관계가 확인되어 학교폭력 절차와 연결해 검토할 수 있습니다.";
        } else if (hasConfirmedNonSchoolRelationship(t)) {
            relationship = "상대가 학교 관계자가 아닌 것으로 확인되어 학교폭력 해당성은 낮게 봅니다.";
        } else {
            relationship = "상대와 학교 관계가 아직 불명확해 학교폭력 해당성 판단에 한계가 있습니다.";
        }

        String pattern;
        if (containsAny(t, "지금도 계속", "아직도", "계속", "반복", "매일", "여러 번", "몇 번")) {
            pattern = "반복 또는 지속 가능성이 확인되어 단발 사안보다 위험도를 높게 봅니다.";
        } else if (containsAny(t, "한 번", "현재까지는 한 번", "1번", "처음")) {
            pattern = "현재 정보상 1회성 사안으로 보며, 추가 반복 여부 확인이 중요합니다.";
        } else {
            pattern = "발생 시점과 반복성이 제한적으로만 확인되어 추가 진술이 있으면 판단이 달라질 수 있습니다.";
        }

        String evidence;
        if (readiness.status().contains("가해")) {
            if (containsAny(t, "캡처", "url", "게시", "댓글", "메시지", "녹음", "삭제", "사과", "담임", "보호자")) {
                evidence = "본인이 한 행동, 중단·삭제 여부, 사과·상담 경과를 확인할 자료가 일부 있습니다.";
            } else {
                evidence = "본인이 한 행동과 피해 회복 조치를 시간순으로 정리할 자료가 아직 부족합니다.";
            }
        } else if (containsAny(t, "진단", "병원", "치료", "진단서")) {
            evidence = "진단·치료 기록이 있어 신체 피해 입증력이 비교적 높습니다.";
        } else if (containsAny(t, "캡처", "url", "녹음", "사진", "메시지", "목격")) {
            evidence = "캡처·사진·메시지·목격 등 직접 증거 단서가 확인됩니다.";
        } else if (containsAny(t, "아직 확보한 증거는 없습니다", "증거는 없습니다", "아직 없음")) {
            evidence = "증거가 없다고 확인되어 사실관계 입증력은 낮습니다.";
        } else {
            evidence = "증거 상태가 약하거나 불명확해 리포트 신뢰도가 제한됩니다.";
        }

        int confirmed = 0;
        if (!types.isEmpty()) confirmed++;
        if (hasDefiniteSchoolRelationship(t) || hasConfirmedNonSchoolRelationship(t)) confirmed++;
        if (containsAny(t, "한 번", "여러 번", "계속", "반복", "오늘", "어제", "지난", "방금", "개월", "주일")) confirmed++;
        if (containsAny(t, "캡처", "url", "녹음", "사진", "진단", "병원", "목격", "증거", "아직 확보한 증거는 없습니다")) confirmed++;
        if (readiness.ready()) confirmed++;

        String confidenceLevel;
        String confidenceReason;
        if (confirmed >= 5 && readiness.schoolViolenceLikely()) {
            confidenceLevel = "높음";
            confidenceReason = "행위, 관계, 시점, 증거 상태가 함께 확인됐습니다.";
        } else if (confirmed >= 4) {
            confidenceLevel = "중간";
            confidenceReason = "핵심 사실은 확인됐지만 학교폭력 해당성 또는 증거 강도에 제한이 있습니다.";
        } else {
            confidenceLevel = "낮음";
            confidenceReason = "사안 구조가 충분히 확정되지 않아 결론을 제한적으로 봐야 합니다.";
        }

        return new CaseFacts(role, relationship, pattern, evidence, confidenceLevel, confidenceReason);
    }

    private int estimateUserMessageCount(String text) {
        if (text == null || text.isBlank()) return 0;
        return Math.max(1, text.split("\\s+").length / 18);
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }
}
