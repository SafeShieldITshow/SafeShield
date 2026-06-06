 package com.safeshield.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class LawDataService {

    public record LawEntry(String name, double relevance, String description) {}

    private static final Map<String, List<LawEntry>> LAW_DATABASE = new LinkedHashMap<>();

    static {
        LAW_DATABASE.put("신체 폭력", List.of(
                new LawEntry("학교폭력예방법 제2조(학교폭력의 정의)", 0.92, "상해, 폭행, 감금, 협박 등 신체 피해를 학교폭력으로 봅니다."),
                new LawEntry("학교폭력예방법 제17조(가해학생에 대한 조치)", 0.88, "서면사과부터 전학, 퇴학까지 사안에 따라 조치할 수 있습니다."),
                new LawEntry("형법 제260조(폭행)", 0.82, "사람의 신체에 폭행을 가한 경우 적용될 수 있습니다."),
                new LawEntry("형법 제257조(상해)", 0.78, "상처나 치료가 필요한 피해가 있으면 적용될 수 있습니다.")
        ));
        LAW_DATABASE.put("언어 폭력", List.of(
                new LawEntry("학교폭력예방법 제2조(학교폭력의 정의)", 0.86, "명예훼손, 모욕, 협박도 학교폭력에 포함됩니다."),
                new LawEntry("형법 제283조(협박)", 0.8, "해악을 고지해 공포심을 주는 경우 적용될 수 있습니다."),
                new LawEntry("형법 제311조(모욕)", 0.72, "공연히 사람을 모욕한 경우 적용될 수 있습니다.")
        ));
        LAW_DATABASE.put("사이버 폭력", List.of(
                new LawEntry("학교폭력예방법 제2조(사이버폭력)", 0.94, "정보통신망을 이용한 따돌림, 명예훼손, 모욕을 학교폭력으로 봅니다."),
                new LawEntry("형법 제307조(명예훼손)", 0.86, "온라인에서 사실이나 허위사실을 공개해 명예를 훼손한 경우 적용 여부를 검토할 수 있습니다."),
                new LawEntry("형법 제311조(모욕)", 0.8, "온라인에서 공연히 사람을 모욕한 경우 적용 여부를 검토할 수 있습니다.")
        ));
        LAW_DATABASE.put("따돌림", List.of(
                new LawEntry("학교폭력예방법 제2조(따돌림)", 0.9, "집단적으로 상대를 배제하거나 심리적 피해를 주는 행위를 포함합니다."),
                new LawEntry("학교폭력예방법 제16조(피해학생 보호)", 0.84, "상담, 일시보호, 치료, 학급교체 등 보호 조치가 가능합니다."),
                new LawEntry("학교폭력예방법 제17조(가해학생에 대한 조치)", 0.8, "사안에 따라 접촉 금지, 봉사, 특별교육 등을 할 수 있습니다.")
        ));
        LAW_DATABASE.put("성폭력", List.of(
                new LawEntry("학교폭력예방법 제2조(학교폭력의 정의)", 0.9, "성폭력도 학교폭력 사안으로 처리될 수 있습니다."),
                new LawEntry("성폭력처벌법 제13조(통신매체 이용 음란)", 0.82, "통신매체를 이용한 성적 수치심 유발 행위에 적용될 수 있습니다."),
                new LawEntry("아동·청소년성보호법 제7조", 0.78, "청소년 대상 성범죄 사안에 적용될 수 있습니다.")
        ));
        LAW_DATABASE.put("스토킹", List.of(
                new LawEntry("스토킹처벌법 제2조(정의)", 0.88, "반복적 접근, 연락, 기다림 등이 스토킹 행위에 해당할 수 있습니다."),
                new LawEntry("스토킹처벌법 제18조(벌칙)", 0.8, "스토킹범죄에 대한 처벌 근거입니다."),
                new LawEntry("학교폭력예방법 제2조(학교폭력의 정의)", 0.72, "학교 관계 안에서 발생하면 학교폭력 절차로도 다룰 수 있습니다.")
        ));
        LAW_DATABASE.put("갈취", List.of(
                new LawEntry("학교폭력예방법 제2조(강요·강제 심부름)", 0.88, "금품 갈취, 강요, 강제 심부름은 학교폭력에 포함됩니다."),
                new LawEntry("형법 제350조(공갈)", 0.84, "협박으로 재산상 이익을 얻은 경우 적용될 수 있습니다."),
                new LawEntry("학교폭력예방법 제17조(가해학생에 대한 조치)", 0.78, "가해학생 조치의 법적 근거입니다.")
        ));
    }

    public List<String> getMatchedLaws(List<String> violenceTypes) {
        List<String> laws = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String type : violenceTypes) {
            for (LawEntry entry : LAW_DATABASE.getOrDefault(type, List.of())) {
                if (seen.add(entry.name())) laws.add(entry.name());
            }
        }
        if (laws.isEmpty()) laws.add("학교폭력예방법 제2조(학교폭력의 정의)");
        return laws;
    }

    public List<Double> getRelevanceScores(List<String> violenceTypes) {
        List<Double> scores = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String type : violenceTypes) {
            for (LawEntry entry : LAW_DATABASE.getOrDefault(type, List.of())) {
                if (seen.add(entry.name())) scores.add(entry.relevance());
            }
        }
        if (scores.isEmpty()) scores.add(0.7);
        return scores;
    }
}
