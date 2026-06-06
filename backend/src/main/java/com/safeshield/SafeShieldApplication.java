package com.safeshield;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})
public class SafeShieldApplication {
    public static void main(String[] args) {
        Dotenv.configure().ignoreIfMissing().load()
                .entries().forEach(e -> System.setProperty(e.getKey(), e.getValue()));
        SpringApplication.run(SafeShieldApplication.class, args);
    }
}
