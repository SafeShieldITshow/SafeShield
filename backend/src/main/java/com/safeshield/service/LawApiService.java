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
        if (v != null) sb.append(v).append("\n\n");
    }

    public Map<String, Object> getStatus() {
        return Map.of(
                "configured", oc != null && !oc.isBlank(),
                "ready", cache.containsKey("학교폭력예방법"),
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
