package com.safeshield.service;

import jakarta.annotation.PostConstruct;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentHashMap.KeySetView;

@Service
public class LawApiService {

    private record LawDefinition(String name, List<String> articles) {}

    @Value("${law.api.oc:}")
    private String oc;

    private final RestTemplate rt;

    public LawApiService() {
        CloseableHttpClient httpClient = HttpClients.custom()
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(10))
                        .setResponseTimeout(Timeout.ofSeconds(15))
                        .build())
                .build();
        rt = new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient));
    }

    private HttpEntity<Void> lawHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36");
        h.set("Accept", "application/json, text/plain, */*");
        h.set("Referer", "https://www.law.go.kr/");
        return new HttpEntity<>(h);
    }
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private final KeySetView<String, Boolean> loadingKeys = ConcurrentHashMap.newKeySet();

    private static final String SEARCH_URL = "https://www.law.go.kr/DRF/lawSearch.do";
    private static final String CONTENT_URL = "https://www.law.go.kr/DRF/lawService.do";
    private static final Map<String, LawDefinition> DEFINITIONS = Map.ofEntries(
            Map.entry("학교폭력예방법", new LawDefinition("학교폭력 예방 및 대책에 관한 법률",
                    List.of("2", "16", "17"))),
            Map.entry("소년법", new LawDefinition("소년법", List.of("2", "4", "32"))),
            Map.entry("형법_폭력", new LawDefinition("형법",
                    List.of("257", "258", "260", "261", "283", "307", "311", "350"))),
            Map.entry("성폭력처벌법", new LawDefinition("성폭력범죄의 처벌 등에 관한 특례법",
                    List.of("2", "13", "14"))),
            Map.entry("아청법", new LawDefinition("아동·청소년의 성보호에 관한 법률", List.of("7", "8"))),
            Map.entry("아동복지법", new LawDefinition("아동복지법", List.of("3", "17", "71"))),
            Map.entry("초중등교육법", new LawDefinition("초·중등교육법", List.of("18"))),
            Map.entry("민법_배상", new LawDefinition("민법", List.of("750", "751", "756"))),
            Map.entry("스토킹처벌법", new LawDefinition("스토킹범죄의 처벌 등에 관한 법률",
                    List.of("2", "18")))
    );
    private static final Map<String, String> FALLBACK_CONTEXTS = Map.ofEntries(
            Map.entry("학교폭력예방법", """
                    === 학교폭력 예방 및 대책에 관한 법률 ===
                    제2조 (정의): 상해, 폭행, 감금, 협박, 약취·유인, 명예훼손, 모욕, 공갈, 강요·강제적인 심부름, 성폭력, 따돌림, 사이버폭력 등을 학교폭력으로 봅니다.
                    제16조 (피해학생의 보호): 피해학생 보호를 위해 상담, 일시보호, 치료 및 치료를 위한 요양, 학급교체 등 필요한 조치를 할 수 있습니다.
                    제17조 (가해학생에 대한 조치): 피해학생에 대한 서면사과, 접촉·협박·보복행위 금지, 학교봉사, 사회봉사, 특별교육, 출석정지, 학급교체, 전학, 퇴학처분 등이 가능합니다.
                    """),
            Map.entry("형법_폭력", """
                    === 형법 ===
                    제257조 (상해): 사람의 신체를 상해한 경우 적용될 수 있습니다.
                    제258조 (중상해): 생명 위험이나 불구 등 중한 상해가 있으면 적용될 수 있습니다.
                    제260조 (폭행): 사람의 신체에 폭행을 가한 경우 적용될 수 있습니다.
                    제261조 (특수폭행): 단체나 다중의 위력을 보이거나 위험한 물건을 휴대해 폭행한 경우 적용될 수 있습니다.
                    제283조 (협박): 사람을 협박한 경우 적용될 수 있습니다.
                    제307조 (명예훼손): 공연히 사실이나 허위사실을 적시해 명예를 훼손한 경우 적용될 수 있습니다.
                    제311조 (모욕): 공연히 사람을 모욕한 경우 적용될 수 있습니다.
                    제350조 (공갈): 공갈로 재물이나 재산상 이익을 얻은 경우 적용될 수 있습니다.
                    """),
            Map.entry("성폭력처벌법", """
                    === 성폭력범죄의 처벌 등에 관한 특례법 ===
                    제2조 (정의): 성폭력범죄의 범위를 정합니다.
                    제13조 (통신매체를 이용한 음란행위): 통신매체를 통해 성적 수치심이나 혐오감을 일으키는 말, 글, 그림, 영상 등을 도달하게 한 경우 적용될 수 있습니다.
                    제14조 (카메라 등을 이용한 촬영): 동의 없이 신체를 촬영하거나 반포한 경우 적용될 수 있습니다.
                    """),
            Map.entry("아청법", """
                    === 아동·청소년의 성보호에 관한 법률 ===
                    제7조 (아동·청소년에 대한 강간·강제추행 등): 아동·청소년 대상 성범죄에 적용될 수 있습니다.
                    제8조 (장애인인 아동·청소년에 대한 간음 등): 장애가 있는 아동·청소년 대상 성범죄에 적용될 수 있습니다.
                    """),
            Map.entry("아동복지법", """
                    === 아동복지법 ===
                    제3조 (정의): 아동과 보호자 등에 관한 기본 정의를 정합니다.
                    제17조 (금지행위): 아동에게 신체적·정서적 학대행위 등을 해서는 안 됩니다.
                    제71조 (벌칙): 금지행위 위반에 대한 벌칙을 정합니다.
                    """),
            Map.entry("초중등교육법", """
                    === 초·중등교육법 ===
                    제18조 (학생의 징계): 학교장이 교육상 필요하다고 인정할 때 학생을 징계할 수 있습니다.
                    """),
            Map.entry("민법_배상", """
                    === 민법 ===
                    제750조 (불법행위의 내용): 고의 또는 과실로 타인에게 손해를 가한 경우 배상책임이 생길 수 있습니다.
                    제751조 (재산 이외의 손해의 배상): 정신적 손해에 대한 배상도 청구할 수 있습니다.
                    제756조 (사용자의 배상책임): 감독 관계가 있는 경우 책임 여부를 검토할 수 있습니다.
                    """),
            Map.entry("스토킹처벌법", """
                    === 스토킹범죄의 처벌 등에 관한 법률 ===
                    제2조 (정의): 상대방 의사에 반해 접근, 연락, 기다림 등을 반복하는 행위를 스토킹행위로 봅니다.
                    제18조 (벌칙): 스토킹범죄에 대한 벌칙을 정합니다.
                    """)
    );

    @PostConstruct
    public void init() {
        if (oc == null || oc.isBlank()) {
            System.out.println("[LawAPI] 국가법령정보센터 연동 비활성화");
            return;
        }
        System.out.println("[LawAPI] 핵심 법령 비동기 로딩 시작");
        scheduleLoad("학교폭력예방법");
        scheduleLoad("형법_폭력");
    }

    private void scheduleLoad(String key) {
        if (oc == null || oc.isBlank() || cache.containsKey(key) || !loadingKeys.add(key)) return;
        LawDefinition definition = DEFINITIONS.get(key);
        if (definition == null) {
            loadingKeys.remove(key);
            return;
        }

        CompletableFuture.runAsync(() -> preload(key, definition))
                .whenComplete((unused, error) -> loadingKeys.remove(key));
    }

    private void preload(String key, LawDefinition definition) {
        try {
            String text = fetchLawArticles(definition.name(), definition.articles());
            if (!text.isBlank()) {
                cache.put(key, text);
                System.out.println("[LawAPI] 캐시 완료: " + definition.name());
            }
        } catch (Exception e) {
            System.err.println("[LawAPI] 로드 실패: " + definition.name()
                    + " (" + e.getClass().getSimpleName() + ")");
        }
    }

    public String getContextForViolenceTypes(List<String> violenceTypes) {
        StringBuilder sb = new StringBuilder();
        appendOrFetch(sb, "학교폭력예방법");

        if (violenceTypes.contains("신체 폭력")) {
            appendOrFetch(sb, "형법_폭력");
            appendOrFetch(sb, "아동복지법");
            appendOrFetch(sb, "민법_배상");
        }
        if (violenceTypes.contains("언어 폭력")) {
            appendOrFetch(sb, "형법_폭력");
        }
        if (violenceTypes.contains("사이버 폭력")) {
            appendOrFetch(sb, "형법_폭력");
        }
        if (violenceTypes.contains("성폭력")) {
            appendOrFetch(sb, "성폭력처벌법");
            appendOrFetch(sb, "아청법");
        }
        if (violenceTypes.contains("스토킹")) {
            appendOrFetch(sb, "스토킹처벌법");
        }
        if (violenceTypes.contains("갈취")) {
            appendOrFetch(sb, "형법_폭력");
            appendOrFetch(sb, "민법_배상");
        }
        return sb.toString().trim();
    }

    private void appendOrFetch(StringBuilder sb, String key) {
        String v = cache.get(key);
        if (v == null && oc != null && !oc.isBlank()) {
            LawDefinition definition = DEFINITIONS.get(key);
            if (definition != null) {
                preload(key, definition);
                v = cache.get(key);
            }
        }
        if (v == null) v = FALLBACK_CONTEXTS.get(key);
        if (v != null) sb.append(v).append("\n\n");
    }

    public Map<String, Object> getStatus() {
        return Map.of(
                "configured", oc != null && !oc.isBlank(),
                "ready", cache.containsKey("학교폭력예방법") || FALLBACK_CONTEXTS.containsKey("학교폭력예방법"),
                "cached", cache.size(),
                "loading", loadingKeys.size(),
                "available", DEFINITIONS.size()
        );
    }

    private String fetchLawArticles(String lawName, List<String> targetArticles) throws Exception {
        String mst = searchLawMst(lawName);
        if (mst == null) {
            return "";
        }
        return extractArticles(mst, lawName, targetArticles);
    }

    private String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @SuppressWarnings("unchecked")
    private String searchLawMst(String lawName) {
        String urlStr = SEARCH_URL + "?OC=" + oc
                + "&target=law&type=JSON&query=" + encode(lawName)
                + "&display=10&page=1";

        ResponseEntity<Map> res = rt.exchange(URI.create(urlStr), HttpMethod.GET, lawHeaders(), Map.class);
        Map<String, Object> body = res.getBody();
        if (body == null) return null;
        Map<String, Object> search = (Map<String, Object>) body.get("LawSearch");
        if (search == null) return null;

        Object lawObj = search.get("law");
        List<Map<String, Object>> laws;
        if (lawObj instanceof List) {
            laws = (List<Map<String, Object>>) lawObj;
        } else if (lawObj instanceof Map) {
            laws = List.of((Map<String, Object>) lawObj);
        } else {
            return null;
        }

        if (laws.isEmpty()) return null;
        String expected = normalizeLawName(lawName);
        Map<String, Object> law = laws.stream()
                .filter(candidate -> expected.equals(normalizeLawName(
                        String.valueOf(candidate.getOrDefault("법령명한글", "")))))
                .findFirst()
                .orElse(laws.get(0));

        String mst = (String) law.get("법령MST");
        if (mst == null) mst = (String) law.get("법령ID");
        if (mst == null && law.get("id") != null) mst = String.valueOf(law.get("id"));
        return mst;
    }

    @SuppressWarnings("unchecked")
    private String extractArticles(String mst, String lawName, List<String> targetArticles) {
        String urlStr = CONTENT_URL + "?OC=" + oc
                + "&target=law&type=JSON&ID=" + mst;

        ResponseEntity<Map> res = rt.exchange(URI.create(urlStr), HttpMethod.GET, lawHeaders(), Map.class);
        Map<String, Object> body = res.getBody();
        if (body == null) return "";

        Map<String, Object> law = (Map<String, Object>) body.get("법령");
        if (law == null) return "";

        Map<String, Object> joMun = (Map<String, Object>) law.get("조문");
        if (joMun == null) return "";

        Object unitObj = joMun.get("조문단위");
        if (unitObj == null) return "";
        List<Map<String, Object>> units;
        if (unitObj instanceof List) {
            units = (List<Map<String, Object>>) unitObj;
        } else if (unitObj instanceof Map) {
            units = List.of((Map<String, Object>) unitObj);
        } else {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(lawName).append(" ===\n");

        for (Map<String, Object> unit : units) {
            String num = String.valueOf(unit.getOrDefault("조문번호", ""));
            String title = String.valueOf(unit.getOrDefault("조문제목", ""));
            String content = String.valueOf(unit.getOrDefault("조문내용", ""));

            boolean match = targetArticles.stream().anyMatch(target -> articleMatches(num, target));
            if (!match) continue;

            sb.append("제").append(num).append("조");
            if (!title.isBlank() && !title.equals("null")) sb.append(" (").append(title).append(")");
            sb.append(": ").append(content).append("\n");

            Object hangObj = unit.get("항");
            if (hangObj instanceof List) {
                for (Map<String, Object> hang : (List<Map<String, Object>>) hangObj) {
                    String hangNum = String.valueOf(hang.getOrDefault("항번호", ""));
                    String hangContent = String.valueOf(hang.getOrDefault("항내용", ""));
                    if (!hangContent.isBlank() && !hangContent.equals("null"))
                        sb.append("  ").append(hangNum).append(". ").append(hangContent).append("\n");
                }
            }
        }
        return sb.toString();
    }

    static String normalizeLawName(String value) {
        if (value == null) return "";
        return value.replaceAll("<[^>]+>", "")
                .replace(" ", "")
                .replace("·", "")
                .trim();
    }

    static boolean articleMatches(String actual, String target) {
        return normalizeArticle(actual).equals(normalizeArticle(target));
    }

    private static String normalizeArticle(String value) {
        if (value == null) return "";
        return value.trim()
                .replace("제", "")
                .replace("조", "")
                .replace(" ", "");
    }
}
