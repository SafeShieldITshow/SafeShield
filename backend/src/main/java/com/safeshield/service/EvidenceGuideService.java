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
        EVIDENCE_DATABASE.put("언어 폭력", List.of("녹음 파일 원본", "발언 직후 사건 메모", "목격자 이름과 연락처", "대화 캡처"));
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

        if (types.contains("사이버 폭력")) {
            if (containsAny(text, "게시", "댓글", "sns", "인스타", "온라인")) {
                evidence.add("게시물 전체 화면 캡처");
                evidence.add("URL·작성자·게시시간 기록");
            }
            if (containsAny(text, "사진", "이미지", "영상")) evidence.add("원본 이미지·댓글 백업");
            if (containsAny(text, "dm", "디엠", "카톡", "메시지", "단톡", "대화")) evidence.add("대화방 전체 캡처");
            evidence.add("계정 프로필 캡처");
        }

        if (types.contains("신체 폭력")) {
            evidence.add("상처 사진 촬영");
            if (containsAny(text, "병원", "진단", "치료", "상해", "멍", "출혈", "골절")) evidence.add("진단서·진료 기록");
            evidence.add("목격자 이름과 연락처");
        }

        if (types.contains("언어 폭력")) {
            if (containsAny(text, "카톡", "메시지", "댓글", "dm", "디엠")) evidence.add("대화 캡처");
            evidence.add("녹음 파일 원본");
            evidence.add("발언 직후 사건 메모");
        }

        if (types.contains("따돌림")) {
            evidence.add("반복 상황 일지");
            if (containsAny(text, "단톡", "초대", "대화방", "카톡")) evidence.add("대화방·초대 제외 화면");
            evidence.add("담임 공유 기록");
        }

        if (types.contains("성폭력")) {
            evidence.add("대화·사진 원본 보관");
            evidence.add("진단서·상담 기록");
            evidence.add("피해 직후 사건 메모");
        }

        if (types.contains("스토킹")) {
            evidence.add("연락·방문 시간 기록");
            evidence.add("통화·메시지 내역");
            evidence.add("위치·동선 기록");
        }

        if (types.contains("갈취")) {
            evidence.add("요구 메시지 캡처");
            evidence.add("송금·결제 내역");
            evidence.add("물건 피해 사진");
        }

        for (String type : types) {
            for (String item : EVIDENCE_DATABASE.getOrDefault(type, List.of())) {
                evidence.add(item);
            }
        }
        if (evidence.isEmpty()) {
            evidence.addAll(List.of("사건 일지", "대화 캡처", "목격자 이름과 연락처", "담임 공유 기록"));
        }
        evidence.add("사건 일지");
        return new ArrayList<>(evidence).stream().limit(6).toList();
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) return true;
        }
        return false;
    }
}
