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
        List<String> actions = buildRecommendedActions(readiness, violenceTypes, text);

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
                "따돌", "돈", "갈취", "성희롱", "성추행", "성적으로", "신체 접촉", "원하지 않는", "만졌", "만지는",
                "괴롭", "놀림", "밀쳤", "사과",
                "욕설", "신체 폭력", "사진이나 게시물", "때리거나 밀치는");
        boolean hasRelationshipAnswer = hasDefiniteSchoolRelationship(t)
                || hasConfirmedNonSchoolRelationship(t)
                || hasUnknownActorSignal(t);
        boolean hasTimeOrRepeat = containsAny(t, "오늘", "어제", "며칠", "몇 번", "계속", "반복", "매일", "한 번", "지난",
                "방금", "언제", "개월", "주일", "몇 주", "주 이상", "한 달", "달 정도", "동안", "최근", "지속", "오래",
                "현재까지는 한 번", "여러 번", "지금도 계속", "정확한 시점");
        boolean hasEvidenceOrChannel = containsAny(t, "캡처", "녹음", "사진", "진단", "병원", "목격", "sns", "카톡", "단톡",
                "댓글", "게시", "메시지", "증거", "아직 확보한 증거는 없습니다", "증거는 없습니다");
        boolean hasImpactOrRecovery = hasImpactOrRecovery(t);
        boolean hasUserGoal = hasUserGoal(t);
        boolean hasFinalConfirmation = hasFinalConfirmation(t);

        if (!hasConcreteIncident) missing.add("무슨 일이 있었는지");
        if (!hasRelationshipAnswer) missing.add("상대가 학교 관계자인지");
        if (!hasTimeOrRepeat) missing.add("언제부터 몇 번 있었는지");
        if (!hasEvidenceOrChannel) missing.add("남아 있는 증거가 무엇인지");
        if (!hasImpactOrRecovery) missing.add("피해 영향이나 피해 회복을 위해 이미 한 일이 있는지");
        if (!hasUserGoal) missing.add("원하는 도움이 무엇인지");

        if (hasConcreteIncident) facts.add("구체적인 사건 내용 확인");
        if (hasRelationshipAnswer) facts.add(hasUnknownActorSignal(t) ? "상대방 특정 불명확 상태 확인" : "상대방과 학교 관계 확인");
        if (hasTimeOrRepeat) facts.add("시점 또는 반복성 단서 확인");
        if (hasEvidenceOrChannel) facts.add("증거 또는 발생 경로 단서 확인");
        if (hasImpactOrRecovery) facts.add("피해 영향 또는 회복 노력 확인");
        if (hasUserGoal) facts.add("요청한 도움 방향 확인");
        if (hasFinalConfirmation) facts.add("같은 사안 여부와 리포트 생성 의사 확인");

        String role = detectRole(t);
        boolean schoolContext = hasSchoolContext(t);
        List<String> violenceTypes = detectViolenceTypes(t);
        boolean hasViolenceType = !violenceTypes.isEmpty();
        int requiredUserMessages = requiredUserMessageCount(t, role, violenceTypes);
        boolean enoughConversation = userMessageCount >= requiredUserMessages;
        boolean coreFactsReady = relevantInput
                && hasConcreteIncident
                && hasRelationshipAnswer
                && hasTimeOrRepeat
                && hasEvidenceOrChannel
                && hasImpactOrRecovery
                && hasUserGoal;
        if (relevantInput && !enoughConversation) {
            missing.add("상담 내용을 조금 더 들은 뒤 리포트 생성");
        }
        boolean ready = relevantInput && enoughConversation && missing.isEmpty();
        boolean lowSchoolViolence = ready && (!schoolContext || !hasViolenceType);

        String status;
        String reason;
        if (!relevantInput) {
            status = "상담 내용 확인 필요";
            reason = "학교폭력 상담과 직접 관련된 사건 내용이 아직 확인되지 않았습니다.";
        } else if (!ready) {
            status = "추가 확인 필요";
            reason = "리포트 생성 전에 사안 구조와 요청한 도움 방향을 더 확인해야 합니다.";
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

        return new ReportReadiness(ready, status, reason, missing.stream().distinct().toList(), facts, relevantInput && !lowSchoolViolence);
    }

    private boolean hasImpactOrRecovery(String text) {
        return hasNoMeaningfulImpact(text) || containsAny(text,
                "무서", "불안", "힘들", "괴롭", "잠", "등교", "학교 가기", "상처", "멍", "병원", "치료",
                "사과", "삭제", "멈췄", "그만", "담임", "보호자", "부모", "상담", "신고", "말했", "공유",
                "회복", "반성", "재발", "차단", "울", "스트레스", "죄책감", "미안");
    }

    private boolean hasNoMeaningfulImpact(String text) {
        return containsAny(text,
                "영향 없음", "영향은 없", "영향이 없", "생활 영향 없음", "생활 영향은 없", "생활 영향이 없",
                "큰 영향 없음", "큰 영향은 없", "크게 영향", "불편한 사항 없음", "불편한 사항은 없", "불편한 사항이 없",
                "불편한 점 없음", "불편한 점은 없", "불편한 점이 없", "불편한 건 없", "불편한 것은 없",
                "불편하지 않", "큰 불편 없음", "크게 불편", "괜찮", "문제 없음", "문제는 없",
                "별 영향", "해당 없음", "기분 때문", "그냥 기분", "기분이 상한 정도");
    }

    private boolean hasUserGoal(String text) {
        return containsAny(text,
                "어떻게", "뭘 해야", "도와", "신고", "리포트", "증거", "처벌", "조치", "멈추", "사과",
                "화해", "보호", "상담", "알려", "대처", "해결", "피해 회복", "재발 방지", "부모님", "담임");
    }

    private boolean hasFinalConfirmation(String text) {
        return containsAny(text,
                "리포트를 생성해도 됩니다", "리포트 생성해도 됩니다", "이 내용으로 리포트", "이 내용으로 분석",
                "하나의 같은 사안", "같은 사안", "최종 확인", "위 내용으로 분석", "위 내용으로 리포트");
    }

    private boolean isRelevantConsultationInput(String text) {
        if (text.isBlank()) return false;
        return containsAny(text,
                "학교", "같은 반", "반 친구", "친구", "선배", "후배", "동급생", "학생", "담임", "선생", "학원",
                "때렸", "맞았", "폭행", "밀쳤", "멍", "상처", "욕", "모욕", "비방", "협박", "따돌", "왕따",
                "sns", "카톡", "단톡", "dm", "디엠", "게시", "댓글", "사진", "성추행", "성희롱", "성적으로",
                "신체 접촉", "원하지 않는", "만졌", "만지는", "불쾌", "갈취", "스토킹",
                "가해", "피해", "증거", "캡처", "신고", "117", "리포트", "상담");
    }

    private List<String> detectViolenceTypes(String text) {
        String t = normalize(text);
        List<String> types = new ArrayList<>();
        boolean mildExpressionOnly = isMildExpressionOrMisunderstanding(t);

        if (containsAny(t, "때렸", "맞았", "폭행", "밀쳤", "발로", "주먹", "상처", "멍", "상해", "다쳤")) {
            types.add("신체 폭력");
        }
        if (!mildExpressionOnly && containsAny(t, "욕", "협박", "모욕", "비하", "비방", "놀림", "죽이", "꺼져", "소문", "명예훼손")) {
            types.add("언어 폭력");
        }
        if (containsAny(t, "sns", "카톡", "카카오", "단톡", "단체 채팅", "디엠", "dm", "인스타", "게시", "댓글", "사진 올", "온라인")) {
            types.add("사이버 폭력");
        }
        if (containsAny(t, "따돌", "왕따", "무시", "배제", "끼워주지", "혼자", "소외")) {
            types.add("따돌림");
        }
        if (containsAny(t, "성추행", "성희롱", "성적", "성적으로", "신체 접촉", "몸을 만", "강제로 만",
                "원하지 않는", "만졌", "만지는", "수치심", "불쾌")) {
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

    private int requiredUserMessageCount(String text, String role, List<String> types) {
        int required = types.contains("성폭력") ? 5 : 7;
        if (!types.contains("성폭력") && ("가해 또는 연루".equals(role) || types.size() >= 2)) required = 8;
        if (!types.contains("성폭력") && containsAny(text, "단톡", "단체 채팅", "여러 명", "무리", "보복", "협박", "지금도", "아직도")) required = Math.max(required, 8);
        if (!types.contains("성폭력") && containsAny(text, "갈취", "스토킹", "흉기", "칼", "골절", "응급실")) required = Math.max(required, 9);
        return required;
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
        boolean noMedicalOrInjury = containsAny(t, "병원 안", "병원은 안", "병원 가지", "치료 안", "진단서 없",
                "멍 없음", "멍은 없", "상처 없음", "상처는 없", "다친 곳 없", "다친 곳은 없", "크게 다치지", "다치지는 않");
        boolean noThreat = containsAny(t, "협박 없", "협박은 없", "보복 걱정 없", "때리겠다는 말은 없", "위협은 없");
        boolean distress = severeDistress
                || containsAny(t, "무서", "불안", "학교 못", "등교 못", "잠을 못", "상담")
                || (containsAny(t, "병원") && !noMedicalOrInjury);
        boolean directThreat = containsAny(t, "죽이", "때리겠", "찾아가", "가만 안", "협박") && !noThreat;
        boolean weaponOrGroup = containsAny(t, "흉기", "칼", "위험한 물건", "여러 명", "단체로", "무리");
        boolean resolved = containsAny(t, "삭제", "사과", "멈췄", "그만뒀", "해결");
        boolean mildExpression = isMildExpressionOrMisunderstanding(t);
        boolean noMeaningfulImpact = hasNoMeaningfulImpact(t);
        boolean minorContext = containsAny(t, "별거 아니", "사소", "가벼운", "오해", "장난", "실수", "기분만", "기분이 상한 정도");

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
        if (mildExpression) score -= 0.8;
        if (noMeaningfulImpact) score -= 0.5;

        double injuryScore = 0.0;
        if (containsAny(t, "멍", "상처", "다쳤")) injuryScore = Math.max(injuryScore, 0.5);
        if (containsAny(t, "진단", "치료", "병원", "상해")) injuryScore = Math.max(injuryScore, 0.9);
        if (containsAny(t, "출혈", "골절", "응급실", "수술", "기절")) injuryScore = Math.max(injuryScore, 1.3);
        if (noMedicalOrInjury && injuryScore < 1.3) injuryScore = 0.0;
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
            if (mildExpression) factorBasedScore -= 1.2;
            if (noMeaningfulImpact) factorBasedScore -= 0.7;
            score = Math.max(score, factorBasedScore);
        }

        boolean physicalType = violenceTypes.contains("신체 폭력");
        boolean seriousPhysicalSignal = physicalType && (injuryScore >= 0.9 || directThreat || weaponOrGroup);
        boolean severeSignal = severeDistress || injuryScore >= 1.1 || directThreat || weaponOrGroup
                || seriousPhysicalSignal
                || violenceTypes.contains("성폭력")
                || violenceTypes.contains("스토킹")
                || violenceTypes.contains("갈취");
        boolean verbalOrCyberOnly = !violenceTypes.isEmpty()
                && violenceTypes.stream().allMatch(type -> type.equals("언어 폭력") || type.equals("사이버 폭력"));

        double cap = 10.0;
        if (mildExpression && !repeated && !severeSignal) {
            cap = noMeaningfulImpact ? 2.6 : 3.2;
        } else if (verbalOrCyberOnly && oneOff && !repeated && !ongoing && !publicSpread && !identityExposure && !severeSignal
                && (noMeaningfulImpact || minorContext || resolved)) {
            cap = 3.0;
        } else if (verbalOrCyberOnly && oneOff && !repeated && !ongoing && !publicSpread && !identityExposure && !severeSignal) {
            cap = 3.8;
        } else if (verbalOrCyberOnly && !repeated && !severeSignal) {
            cap = publicSpread || identityExposure ? 5.0 : 4.2;
        } else if (verbalOrCyberOnly && !severeSignal) {
            cap = 5.8;
        } else if (physicalType && oneOff && injuryScore <= 0.5 && !directThreat && !weaponOrGroup) {
            cap = 5.3;
        } else if (physicalType && injuryScore <= 0.5 && !directThreat && !weaponOrGroup && (repeated || longTerm || ongoing)) {
            cap = 6.8;
        } else if (physicalType && injuryScore <= 0.5 && !directThreat && !weaponOrGroup) {
            cap = 5.8;
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
        if (isMildExpressionOrMisunderstanding(t)) {
            seriousness = Math.min(seriousness, 1);
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

    private boolean isMildExpressionOrMisunderstanding(String text) {
        boolean mildPhrase = containsAny(text,
                "귀엽", "귀엽다고", "예쁘", "잘생겼", "칭찬", "장난", "농담", "오해", "기분 때문", "기분이 상");
        boolean severePhrase = containsAny(text,
                "협박", "죽이", "꺼져", "비방", "모욕", "명예훼손", "유포", "게시", "사진", "영상",
                "때렸", "맞았", "밀쳤", "멍", "상처", "따돌", "왕따", "돈", "갈취", "성추행", "성희롱");
        return mildPhrase && !severePhrase;
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
        if (isVictimPerspective(text)) {
            return "피해 또는 상담";
        }
        if (isWitnessOrGuardianPerspective(text)) {
            return "목격자 또는 보호자";
        }
        return "피해 또는 상담";
    }

    private boolean isPerpetratorPerspective(String text) {
        boolean ownPhotoPostedVictimContext = hasOwnPhotoPostedVictimContext(text);
        if (isVictimPerspective(text) && !containsAny(text,
                "저는 가해", "제가 가해", "내가 가해", "나는 가해", "본인이 가해",
                "저는 가해자", "제가 가해자", "내가 가해자", "나는 가해자",
                "제가 때렸", "내가 때렸", "제가 욕했", "내가 욕했",
                "제가 한 행동", "내가 한 행동", "제가 올린", "내가 올린")) {
            return false;
        }
        if (ownPhotoPostedVictimContext && !hasExplicitPerpetratorAdmission(text)) {
            return false;
        }
        boolean directPhrase = containsAny(text,
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
        boolean selfActor = containsAny(text, "제가", "내가", "저도", "본인이");
        boolean harmfulAction = containsAny(text,
                "때렸", "밀쳤", "욕했", "욕을 했", "욕설을 했", "올렸", "게시", "댓글을 달",
                "괴롭혔", "따돌", "놀렸", "빼앗", "강요");
        return directPhrase || (selfActor && harmfulAction);
    }

    private boolean hasOwnPhotoPostedVictimContext(String text) {
        return containsAny(text, "제 사진", "내 사진", "저의 사진", "나의 사진")
                && containsAny(text, "sns", "인스타", "게시", "올렸", "올린", "올라왔", "유포", "공유", "퍼졌");
    }

    private boolean hasExplicitPerpetratorAdmission(String text) {
        return containsAny(text,
                "저는 가해", "제가 가해", "내가 가해", "나는 가해", "본인이 가해",
                "저는 가해자", "제가 가해자", "내가 가해자", "나는 가해자",
                "제가 친구 사진", "내가 친구 사진", "제가 상대 사진", "내가 상대 사진",
                "허락 없이 올렸", "몰래 올렸", "장난으로 올렸",
                "제가 한 행동", "내가 한 행동", "괴롭힌 건 저");
    }

    private boolean isVictimPerspective(String text) {
        return containsAny(text,
                "피해를 당", "피해 당", "제가 피해", "저는 피해", "내가 피해", "제가 당", "내가 당",
                "맞아서", "맞았고", "맞았어요", "욕을 먹", "욕먹", "괴롭힘 당", "괴롭힘을 당",
                "신고하려", "신고하고 싶", "사과 받고", "사과 받", "처벌받게", "상대가 저를", "친구가 저를",
                "제 사진", "내 사진", "저의 사진", "나의 사진", "저한테", "저에게");
    }

    private boolean isWitnessOrGuardianPerspective(String text) {
        return containsAny(text,
                "목격", "제가 봤", "내가 봤", "친구가 당", "친구가 맞", "친구가 욕",
                "자녀", "우리 아이", "제 아이", "아이가", "부모입니다", "보호자입니다", "엄마입니다", "아빠입니다");
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

    private boolean hasUnknownActorSignal(String text) {
        return containsAny(text,
                "용의자 특정", "가해자 특정", "상대 특정", "작성자 특정", "계정 특정",
                "특정이 어렵", "특정하기 어렵", "특정할 수 없", "특정 못", "특정 안",
                "누군지 모르", "누구인지 모르", "누가 했는지 모르", "누가 올렸는지 모르",
                "작성자를 모르", "작성자 모름", "계정 주인을 모르", "계정 모름", "익명",
                "모르는 계정", "모르는 사람", "알 수 없", "확인 어렵");
    }

    private List<String> buildKeyFindings(String text, List<String> types, ReportReadiness readiness) {
        CaseFacts facts = analyzeCaseFacts(text, types, readiness);
        List<String> findings = new ArrayList<>();
        findings.add("판단 상태: " + readiness.status());
        findings.add("핵심 상황: " + buildCaseSnapshot(text, types, readiness));
        findings.add("사안 역할: " + facts.role());
        findings.add("관계 판단: " + facts.relationship());
        findings.add(types.isEmpty() ? "행위 유형: 명확한 학교폭력 유형은 아직 확인되지 않았습니다." : "행위 유형: " + String.join(", ", types));
        findings.add("발생 양상: " + facts.incidentPattern());
        findings.add("증거 수준: " + facts.evidenceLevel());
        findings.add("맞춤 판단: " + buildPersonalizedJudgment(text, types, readiness));
        findings.add("판단 신뢰도: " + facts.confidenceLevel() + " - " + facts.confidenceReason());
        findings.add("예상 조치 근거: " + buildMeasureBasis(text, types, readiness));
        return findings.stream().distinct().limit(10).toList();
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

    private List<String> buildRecommendedActions(ReportReadiness readiness, List<String> types, String text) {
        String t = normalize(text);
        if (!readiness.ready()) {
            List<String> actions = new ArrayList<>();
            actions.add("지금은 리포트를 서두르기보다, 무슨 일이 있었는지와 원하는 도움을 조금 더 확인하는 단계입니다.");
            actions.add("말하기 어렵다면 날짜, 상대, 장소, 남아 있는 증거처럼 기억나는 조각만 먼저 적어도 됩니다.");
            if (containsAny(t, "무서", "불안", "보복", "협박", "찾아와")) {
                actions.add("보복이나 접근이 걱정되면 혼자 대응하지 말고 보호자, 담임, 학교전담경찰관 또는 117 상담에 먼저 연결하세요.");
            }
            return actions.stream().distinct().limit(4).toList();
        }
        if (!readiness.schoolViolenceLikely()) {
            return List.of("학교폭력 절차보다 일반 상담 또는 플랫폼 신고 가능성을 먼저 검토하세요.", "학교 관계가 추가로 확인되면 상담 내용을 보완하세요.");
        }
        if (readiness.status().contains("가해")) {
            List<String> actions = new ArrayList<>();
            actions.add("추가 연락, 댓글, 게시, 주변 공유를 즉시 중단하고 보복이나 압박으로 보일 행동을 피하세요.");
            if (types.contains("사이버 폭력")) {
                if (containsAny(t, "단톡", "단체 채팅", "채팅방", "카톡", "메시지")) {
                    actions.add("단톡방이나 메시지에서 한 말은 삭제 전 원본과 수정·삭제 시각을 보관하고, 추가 설명은 직접 보내지 말고 학교나 보호자를 통해 전달하세요.");
                } else {
                    actions.add("게시물·댓글·대화 원본과 삭제·수정 내역을 보관한 뒤, 학교나 보호자와 삭제 및 피해 회복 절차를 진행하세요.");
                }
            }
            if (types.contains("신체 폭력")) {
                actions.add("상대방의 상해 여부와 치료 필요성을 확인하고, 보호자·담임에게 사실관계를 숨기지 말고 설명하세요.");
            }
            if (containsAny(t, "사진", "영상", "유포", "올렸")) {
                actions.add("사진이나 영상이 포함됐다면 더 공유하지 말고, 삭제 요청과 피해 회복은 피해자에게 직접 압박하지 않는 방식으로 진행하세요.");
            }
            actions.add("사과는 직접 압박하지 말고 보호자나 학교를 통해 전달하고, 변명보다 어떤 행동을 중단했고 어떻게 회복하겠는지 적으세요.");
            actions.add("발생 경위, 본인이 한 행동, 중단한 조치, 재발 방지 계획을 시간순으로 작성하세요.");
            if (containsAny(t, "미안", "죄책감", "후회", "반성")) {
                actions.add("죄책감이 크다면 혼자 버티지 말고 담임·상담교사에게 먼저 말해 피해 회복 절차를 같이 정리하세요.");
            }
            return actions.stream().distinct().limit(6).toList();
        }
        List<String> actions = new ArrayList<>();
        if (containsAny(t, "무서", "불안", "보복", "협박", "찾아와", "죽이")) {
            actions.add("안전이 먼저입니다. 등하교 동선, 혼자 있는 시간, 온라인 연락을 줄이고 보호자·담임에게 바로 공유하세요.");
        }
        if (types.contains("신체 폭력")) {
            if (containsAny(t, "멍", "상처", "출혈")) {
                actions.add("멍이나 상처는 오늘 날짜가 남게 가까운 사진과 전체 위치 사진을 나눠 찍고, 통증이 지속되면 병원 기록을 남기세요.");
            } else {
                actions.add("상처 사진과 진료 기록을 먼저 확보하고, 통증이나 상해가 있으면 보호자와 병원 기록을 남기세요.");
            }
        }
        if (types.contains("사이버 폭력")) {
            if (containsAny(t, "단톡", "단체 채팅", "채팅방", "카톡", "메시지")) {
                actions.add("단톡방은 앞뒤 맥락, 참여자 목록, 보낸 시간이 같이 보이게 저장하고 대화방을 바로 나가기 전에 원본을 확보하세요.");
            } else if (containsAny(t, "사진", "영상", "sns", "인스타", "게시")) {
                actions.add("SNS 사진·게시물은 URL, 작성자, 게시 시간, 댓글 흐름을 한 번에 남기고 원본 사진이 퍼진 경로도 따로 적어두세요.");
            } else {
                actions.add("게시물·댓글·계정·URL·게시 시간을 삭제 전 한 화면에 보이게 저장하세요.");
            }
        }
        if (types.contains("갈취")) {
            actions.add("돈이나 물건 요구 메시지와 송금·결제 내역을 금액, 날짜, 상대 계정과 함께 정리하세요.");
        }
        if (types.contains("성폭력")) {
            actions.add("혼자 대응하지 말고 보호자, 학교 전담교사, 전문 상담기관과 즉시 공유하세요.");
        }
        if (containsAny(t, "부모", "보호자", "말 못", "말하기", "망설")) {
            actions.add("바로 길게 설명하기 어렵다면 캡처와 함께 '혼자 해결하기 어렵다'는 한 문장으로 보호자나 담임에게 먼저 보여주세요.");
        }
        if (containsAny(t, "사과", "화해", "친구", "어색")) {
            actions.add("사과나 화해를 원해도 단둘이 해결하려 하지 말고, 담임이나 보호자가 있는 자리에서 재발 방지 조건을 확인하세요.");
        }
        actions.add("사안 구조와 증거를 정리한 뒤 담임 또는 학교폭력 담당자에게 상담을 요청하세요.");
        actions.add("반복되거나 보복 우려가 있으면 117 상담으로 보호 조치 절차를 확인하세요.");
        return actions.stream().distinct().limit(6).toList();
    }

    private String buildCaseSnapshot(String text, List<String> types, ReportReadiness readiness) {
        String t = normalize(text);
        String actor = hasDefiniteSchoolRelationship(t)
                ? "학교 또는 학원 관계의 상대"
                : hasUnknownActorSignal(t) ? "현재 특정하기 어려운 상대" : "상대";
        String channel = detectChannel(t);
        String conduct = detectConductSummary(t, types);
        String pattern = containsAny(t, "지금도", "아직도", "계속", "반복", "매일", "여러 번", "몇 번")
                ? "반복·지속되는 형태"
                : containsAny(t, "한 번", "1번", "처음")
                ? "현재 정보상 1회성에 가까운 형태"
                : "반복성은 추가 확인이 필요한 형태";

        if (readiness.status().contains("가해")) {
            return actor + "에게 " + channel + "에서 " + conduct + " 행동을 했다고 진술한 사안이며, " + pattern + "로 정리됩니다.";
        }
        if (readiness.status().contains("학교폭력 해당성 낮음")) {
            return channel + "에서 " + conduct + " 내용은 있으나 학교 관계가 약해 학교폭력 절차와의 연결은 제한적으로 봅니다.";
        }
        return actor + "로부터 " + channel + "에서 " + conduct + " 피해를 겪었다고 진술한 사안이며, " + pattern + "로 정리됩니다.";
    }

    private String buildPersonalizedJudgment(String text, List<String> types, ReportReadiness readiness) {
        String t = normalize(text);
        if (!readiness.ready()) {
            return "결론보다 사안 구조 확인이 먼저라, 사실관계와 요청한 도움 방향을 더 모아야 합니다.";
        }
        if (readiness.status().contains("가해")) {
            if (containsAny(t, "미안", "죄책감", "후회", "반성")) {
                return "방어보다 중단·원본 보존·피해 회복을 먼저 실행하면 상담과 학교 절차에서 설명 가능한 내용이 생깁니다.";
            }
            return "책임 회피로 보이지 않게 본인이 한 행동, 중단한 조치, 재발 방지 계획을 분리해 정리해야 합니다.";
        }
        if (containsAny(t, "무서", "불안", "보복", "찾아와", "협박")) {
            return "불안과 보복 우려가 확인되어 증거 정리와 동시에 보호자·담임에게 안전 조치를 요청하는 흐름이 적절합니다.";
        }
        if (containsAny(t, "말 못", "말하기", "부모", "보호자", "혼자")) {
            return "혼자 설명하기 부담스러운 상태로 보여, 긴 설명보다 캡처와 짧은 도움 요청 문장부터 전달하는 방식이 맞습니다.";
        }
        if (types.contains("신체 폭력")) {
            return "신체 피해는 시간이 지나면 흔적이 약해질 수 있어 사진·진료 기록을 먼저 확보하는 것이 판단 정확도를 높입니다.";
        }
        if (types.contains("사이버 폭력")) {
            return "온라인 자료는 삭제되기 쉬워 화면 캡처보다 원본 링크·작성자·시간·참여자 정보를 함께 남기는 것이 중요합니다.";
        }
        return "현재 내용은 절차 안내보다 사실관계와 원하는 해결 방향을 함께 정리하는 방식이 필요합니다.";
    }

    private String detectChannel(String text) {
        if (containsAny(text, "단톡", "단체 채팅", "채팅방")) return "단체 채팅방";
        if (containsAny(text, "카톡", "메시지", "dm", "디엠")) return "개별 메시지";
        if (containsAny(text, "sns", "인스타", "게시", "댓글", "온라인")) return "SNS 또는 온라인 공간";
        if (containsAny(text, "교실", "학교", "복도", "운동장", "학원")) return "학교 또는 학원 공간";
        return "확인된 공간";
    }

    private String detectConductSummary(String text, List<String> types) {
        if (types.contains("신체 폭력") && containsAny(text, "때렸", "맞았", "밀쳤", "밀쳐", "멍", "상처")) {
            return "때리거나 밀치는 신체 폭력";
        }
        if (types.contains("사이버 폭력") && containsAny(text, "사진", "영상", "유포", "올렸", "게시")) return "사진·게시물 관련 괴롭힘";
        if (containsAny(text, "욕", "욕설", "비방", "모욕", "놀림")) return "욕설·비방·조롱";
        if (containsAny(text, "따돌", "왕따", "무시", "배제", "혼자")) return "따돌림이나 배제";
        if (containsAny(text, "돈", "갈취", "빼앗", "강요")) return "금품 요구나 강요";
        if (containsAny(text, "스토킹", "따라", "기다리", "찾아와")) return "반복 접근이나 연락";
        if (!types.isEmpty()) return String.join("·", types);
        return "구체적 행위";
    }

    private CaseFacts analyzeCaseFacts(String text, List<String> types, ReportReadiness readiness) {
        String t = normalize(text);
        String role = detectRole(t);

        String relationship;
        if (hasDefiniteSchoolRelationship(t)) {
            relationship = "학교 또는 학원 관계가 확인되어 학교폭력 절차와 연결해 검토할 수 있습니다.";
        } else if (hasUnknownActorSignal(t)) {
            relationship = "상대를 현재 특정하기 어렵다고 확인되어, 학교 관계와 가해자 특정에는 한계가 있습니다.";
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
