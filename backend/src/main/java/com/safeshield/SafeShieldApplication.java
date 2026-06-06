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
        configureDatabaseUrl();
        SpringApplication.run(SafeShieldApplication.class, args);
    }

    private static void configureDatabaseUrl() {
        String explicitUrl = firstValue("SPRING_DATASOURCE_URL", "spring.datasource.url");
        if (explicitUrl != null && !explicitUrl.isBlank()) {
            configureSqliteDialectIfNeeded(explicitUrl);
            return;
        }

        String databaseUrl = firstValue("DATABASE_URL");
        if (databaseUrl == null || databaseUrl.isBlank()) return;

        if (databaseUrl.startsWith("sqlite:")) {
            String sqliteUrl = toSqliteJdbcUrl(databaseUrl);
            System.setProperty("spring.datasource.url", sqliteUrl);
            configureSqliteDialectIfNeeded(sqliteUrl);
            return;
        }

        if (!databaseUrl.startsWith("postgres")) return;
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

    private static void configureSqliteDialectIfNeeded(String url) {
        if (!url.startsWith("jdbc:sqlite:")) return;
        if (!hasValue("SPRING_JPA_DATABASE_PLATFORM") && !hasValue("spring.jpa.database-platform")) {
            System.setProperty("spring.jpa.database-platform", "org.hibernate.community.dialect.SQLiteDialect");
        }
        if (!hasValue("SPRING_DATASOURCE_DRIVER_CLASS_NAME") && !hasValue("spring.datasource.driver-class-name")) {
            System.setProperty("spring.datasource.driver-class-name", "org.sqlite.JDBC");
        }
    }

    private static String toSqliteJdbcUrl(String databaseUrl) {
        if (databaseUrl.startsWith("jdbc:sqlite:")) return databaseUrl;
        if (databaseUrl.startsWith("sqlite:///")) {
            return "jdbc:sqlite:/" + databaseUrl.substring("sqlite:///".length());
        }
        if (databaseUrl.startsWith("sqlite://")) {
            return "jdbc:sqlite:" + databaseUrl.substring("sqlite://".length());
        }
        if (databaseUrl.startsWith("sqlite:")) {
            return "jdbc:" + databaseUrl;
        }
        return databaseUrl;
    }

    private static boolean hasValue(String key) {
        return firstValue(key) != null;
    }

    private static String firstValue(String... keys) {
        for (String key : keys) {
            String envValue = System.getenv(key);
            if (envValue != null && !envValue.isBlank()) return envValue;
            String propertyValue = System.getProperty(key);
            if (propertyValue != null && !propertyValue.isBlank()) return propertyValue;
        }
        return null;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
