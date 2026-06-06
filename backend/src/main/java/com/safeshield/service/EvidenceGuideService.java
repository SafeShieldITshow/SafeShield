package com.safeshield.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class EvidenceGuideService {

    private static final Map<String, List<String>> EVIDENCE_DATABASE = new LinkedHashMap<>();

    static {
        EVIDENCE_DATABASE.put("신체 폭력", List.of("상해 사진", "병원 진단서", "음성 녹음", "목격자 진술", "일지 작성"));
        EVIDENCE_DATABASE.put("언어 폭력", List.of("음성 녹음", "메시지 캡처", "목격자 진술", "일지 작성"));
        EVIDENCE_DATABASE.put("사이버 폭력", List.of("메시지 캡처", "게시물 스크린샷", "접속 기록", "일지 작성"));
        EVIDENCE_DATABASE.put("따돌림", List.of("일지 작성", "목격자 진술", "메시지 캡처"));
        EVIDENCE_DATABASE.put("성폭력", List.of("음성 녹음", "메시지 캡처", "병원 진단서", "목격자 진술"));
        EVIDENCE_DATABASE.put("스토킹", List.of("접속 기록", "메시지 캡처", "일지 작성", "목격자 진술"));
        EVIDENCE_DATABASE.put("갈취", List.of("메시지 캡처", "음성 녹음", "일지 작성", "목격자 진술"));
    }

    public List<String> getEvidenceGuide(List<String> violenceTypes) {
        List<String> evidence = new ArrayList<>();
        for (String type : violenceTypes) {
            for (String item : EVIDENCE_DATABASE.getOrDefault(type, List.of())) {
                if (!evidence.contains(item)) evidence.add(item);
            }
        }
        if (evidence.isEmpty()) {
            evidence.addAll(List.of("음성 녹음", "메시지 캡처", "일지 작성", "목격자 진술"));
        }
        return evidence;
    }
}
