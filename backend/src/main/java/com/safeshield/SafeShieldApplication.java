package com.safeshield;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})
public class SafeShieldApplication {
    public static void main(String[] args) {
        Dotenv.configure().ignoreIfMissing().load()
                .entries().forEach(e -> System.setProperty(e.getKey(), e.getValue()));
        configureRenderDatabaseUrl();
        SpringApplication.run(SafeShieldApplication.class, args);
    }

    private static void configureRenderDatabaseUrl() {
        if (hasValue("SPRING_DATASOURCE_URL") || hasValue("spring.datasource.url")) return;

        String databaseUrl = System.getenv("DATABASE_URL");
        if (databaseUrl == null || databaseUrl.isBlank()) {
            databaseUrl = System.getProperty("DATABASE_URL");
        }
        if (databaseUrl == null || !databaseUrl.startsWith("postgres")) return;

        URI uri = URI.create(databaseUrl.replaceFirst("^postgres://", "postgresql://"));
        String[] userInfo = uri.getUserInfo() == null ? new String[]{"", ""} : uri.getUserInfo().split(":", 2);
        String username = decode(userInfo[0]);
        String password = userInfo.length > 1 ? decode(userInfo[1]) : "";
        int port = uri.getPort() == -1 ? 5432 : uri.getPort();
        String query = uri.getQuery() == null || uri.getQuery().isBlank() ? "" : "?" + uri.getQuery();
        String jdbcUrl = "jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getPath() + query;

        System.setProperty("spring.datasource.url", jdbcUrl);
        if (!hasValue("SPRING_DATASOURCE_USERNAME") && !hasValue("spring.datasource.username")) {
            System.setProperty("spring.datasource.username", username);
        }
        if (!hasValue("SPRING_DATASOURCE_PASSWORD") && !hasValue("spring.datasource.password")) {
            System.setProperty("spring.datasource.password", password);
        }
    }

    private static boolean hasValue(String key) {
        String envValue = System.getenv(key);
        String propertyValue = System.getProperty(key);
        return (envValue != null && !envValue.isBlank()) || (propertyValue != null && !propertyValue.isBlank());
    }

    private static String decode(String value) {
        return URLDecoder.decode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
