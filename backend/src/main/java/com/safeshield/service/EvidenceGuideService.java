package com.safeshield.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

@Service
public class EvidenceGuideService {

    private static final Map<String, List<String>> EVIDENCE_DATABASE = new LinkedHashMap<>();

    static {
        EVIDENCE_DATABASE.put("신체 폭력", List.of("상처 사진 촬영", "진단서·진료 기록", "목격자 이름과 연락처", "사건 일지"));
        EVIDENCE_DATABASE.put("언어 폭력", List.of("발언 직후 사건 메모", "목격자 이름과 연락처", "대화 캡처"));
        EVIDENCE_DATABASE.put("사이버 폭력", List.of("게시물 전체 화면 캡처", "URL·작성자·게시시간 기록", "원본 이미지·댓글 백업", "계정 프로필 캡처"));
        EVIDENCE_DATABASE.put("따돌림", List.of("반복 상황 일지", "대화방·초대 제외 화면", "목격자 이름과 연락처", "담임 공유 기록"));
        EVIDENCE_DATABASE.put("성폭력", List.of("대화·사진 원본 보관", "진단서·상담 기록", "목격자 이름과 연락처", "피해 직후 사건 메모"));
        EVIDENCE_DATABASE.put("스토킹", List.of("연락·방문 시간 기록", "통화·메시지 내역", "위치·동선 기록", "목격자 이름과 연락처"));
        EVIDENCE_DATABASE.put("갈취", List.of("요구 메시지 캡처", "송금·결제 내역", "물건 피해 사진", "목격자 이름과 연락처"));
    }

    public List<String> getEvidenceGuide(List<String> violenceTypes) {
        return getEvidenceGuide(violenceTypes, "");
    }

    public List<String> getEvidenceGuide(List<String> violenceTypes, String userText) {
        String text = userText == null ? "" : userText.toLowerCase(Locale.ROOT);
        List<String> types = violenceTypes == null ? List.of() : violenceTypes;
        Set<String> evidence = new LinkedHashSet<>();
        Set<String> handledTypes = new LinkedHashSet<>();

        if (isPerpetratorPerspective(text)) {
            evidence.add("본인 행동 타임라인");
            if (types.contains("사이버 폭력")) {
                evidence.add("게시물·댓글 원본과 URL");
                evidence.add("삭제·수정 내역 기록");
            }
            if (types.contains("언어 폭력")) {
                evidence.add("발언 경위 메모");
                evidence.add("대화·녹음 원본");
            }
            if (types.contains("신체 폭력") || types.contains("성폭력")) {
                evidence.add("상대방 피해 확인 자료");
            }
            if (types.contains("갈취")) {
                evidence.add("반환·변상 기록");
            }
            evidence.add("사과·피해 회복 기록");
            evidence.add("보호자·담임 상담 기록");
            evidence.add("재발 방지 계획");
            return compactEvidence(evidence);
        }

        if (types.contains("사이버 폭력")) {
            boolean chatBased = containsAny(text, "dm", "디엠", "카톡", "메시지", "단톡", "대화", "채팅방", "단체 채팅");
            boolean postBased = containsAny(text, "게시", "댓글", "sns", "인스타", "온라인");
            boolean mediaBased = containsAny(text, "사진", "이미지", "영상");
            boolean deletedOrReported = containsAny(text, "삭제", "내렸", "신고", "차단");
            if (chatBased) {
                evidence.add("대화방 전체 캡처");
                evidence.add("참여자·계정 정보 정리");
                if (!mediaBased) evidence.add("대화 내보내기 원본");
            }
            if (postBased && !chatBased) evidence.add("게시물 전체 화면 캡처");
            if (postBased) evidence.add("URL·작성자·게시시간 기록");
            if (containsAny(text, "댓글", "공유", "퍼졌", "유포")) evidence.add("댓글·공유 범위 기록");
            if (mediaBased) evidence.add("원본 파일 백업");
            if (deletedOrReported) evidence.add("삭제·신고 처리 기록");
            if (!chatBased && !postBased) evidence.add("계정 프로필 캡처");
            handledTypes.add("사이버 폭력");
        }

        if (types.contains("신체 폭력")) {
            evidence.add("상처 사진 촬영");
            if (containsAny(text, "병원", "진단", "치료", "상해", "멍", "출혈", "골절")) evidence.add("진단서·진료 기록");
            evidence.add("목격자 이름과 연락처");
            if (containsAny(text, "학교", "담임", "보건실", "선생")) evidence.add("보건실·담임 기록");
            handledTypes.add("신체 폭력");
        }

        if (types.contains("언어 폭력")) {
            boolean textBased = containsAny(text, "카톡", "메시지", "댓글", "dm", "디엠", "단톡", "채팅방", "sns");
            if (textBased) evidence.add("대화 캡처");
            if (containsAny(text, "녹음", "통화", "전화", "음성", "협박", "위협")) evidence.add("음성·통화 파일 원본");
            evidence.add("발언 직후 사건 메모");
            evidence.add("목격자 이름과 연락처");
            handledTypes.add("언어 폭력");
        }

        if (types.contains("따돌림")) {
            evidence.add("반복 상황 일지");
            if (containsAny(text, "단톡", "초대", "대화방", "카톡")) evidence.add("대화방·초대 제외 화면");
            evidence.add("담임 공유 기록");
            handledTypes.add("따돌림");
        }

        if (types.contains("성폭력")) {
            evidence.add("대화·사진 원본 보관");
            evidence.add("진단서·상담 기록");
            evidence.add("피해 직후 사건 메모");
            handledTypes.add("성폭력");
        }

        if (types.contains("스토킹")) {
            evidence.add("연락·방문 시간 기록");
            evidence.add("통화·메시지 내역");
            evidence.add("위치·동선 기록");
            handledTypes.add("스토킹");
        }

        if (types.contains("갈취")) {
            evidence.add("요구 메시지 캡처");
            evidence.add("송금·결제 내역");
            evidence.add("물건 피해 사진");
            handledTypes.add("갈취");
        }

        for (String type : types) {
            if (handledTypes.contains(type)) continue;
            for (String item : EVIDENCE_DATABASE.getOrDefault(type, List.of())) {
                evidence.add(item);
            }
        }
        if (evidence.isEmpty()) {
            evidence.addAll(List.of("사건 일지", "대화 캡처", "목격자 이름과 연락처", "담임 공유 기록"));
        }
        evidence.add("사건 일지");
        return compactEvidence(evidence);
    }

    private List<String> compactEvidence(Set<String> evidence) {
        List<String> compacted = new ArrayList<>();
        Set<String> categories = new LinkedHashSet<>();

        for (String item : evidence) {
            String category = evidenceCategory(item);
            if (categories.add(category)) {
                compacted.add(item);
            }
        }
        return compacted.stream().limit(6).toList();
    }

    private String evidenceCategory(String item) {
        if (item.contains("캡처") || item.contains("스크린샷") || item.contains("화면")) return "capture";
        if (item.contains("URL") || item.contains("게시시간")) return "link";
        if (item.contains("계정") || item.contains("프로필") || item.contains("참여자")) return "account";
        if (item.contains("원본") || item.contains("백업")) return "original";
        if (item.contains("일지") || item.contains("메모") || item.contains("타임라인")) return "timeline";
        if (item.contains("목격자") || item.contains("진술")) return "witness";
        if (item.contains("진단서") || item.contains("진료") || item.contains("상담 기록")) return "medical";
        if (item.contains("보건실")) return "school-medical";
        if (item.contains("담임") || item.contains("보호자")) return "school-share";
        if (item.contains("사과") || item.contains("회복") || item.contains("반환") || item.contains("변상")) return "recovery";
        if (item.contains("재발")) return "prevention";
        if (item.contains("삭제") || item.contains("신고 처리")) return "process";
        if (item.contains("공유 범위")) return "spread";
        return item;
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

    private boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) return true;
        }
        return false;
    }
}
