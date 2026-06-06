package com.safeshield.config;

import com.safeshield.model.User;
import com.safeshield.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Component
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;

    public OAuth2SuccessHandler(UserRepository userRepository, JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.jwtUtil = jwtUtil;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();

        String email = oAuth2User.getAttribute("email");
        String name  = oAuth2User.getAttribute("name");
        if (email == null || email.isBlank()) {
            response.sendRedirect("http://localhost:5173/login?oauth_error=email");
            return;
        }

        User user = userRepository.findByEmail(email)
                .or(() -> userRepository.findByUsername(email))
                .orElseGet(() -> {
                    User newUser = new User();
                    newUser.setUsername(email);
                    newUser.setPasswordHash("");
                    newUser.setName(name != null ? name : email);
                    newUser.setEmail(email);
                    return userRepository.save(newUser);
                });

        String token = jwtUtil.generate(user.getId(), user.getUsername());

        response.sendRedirect("http://localhost:5173/oauth2/callback?token=" + encode(token)
                + "&username=" + encode(user.getUsername())
                + "&name=" + encode(user.getName()));
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
