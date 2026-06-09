package com.safeshield.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

@Configuration
public class OAuthClientDiagnostics {

    private static final Logger log = LoggerFactory.getLogger(OAuthClientDiagnostics.class);

    @Bean
    public CommandLineRunner logGoogleOAuthConfig(ClientRegistrationRepository registrations) {
        return args -> {
            ClientRegistration google = registrations.findByRegistrationId("google");
            if (google == null) {
                log.warn("Google OAuth config is missing.");
                return;
            }

            log.info("Google OAuth config loaded. clientIdSuffix={}, authMethod={}, tokenUri={}, redirectUri={}",
                    suffix(google.getClientId()),
                    google.getClientAuthenticationMethod().getValue(),
                    google.getProviderDetails().getTokenUri(),
                    google.getRedirectUri());
        };
    }

    private String suffix(String value) {
        if (value == null || value.isBlank()) {
            return "missing";
        }
        return value.length() <= 8 ? value : value.substring(value.length() - 8);
    }
}
