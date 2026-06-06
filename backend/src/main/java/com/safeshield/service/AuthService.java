package com.safeshield.service;

import com.safeshield.config.JwtUtil;
import com.safeshield.dto.LoginRequest;
import com.safeshield.dto.SignupRequest;
import com.safeshield.model.User;
import com.safeshield.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);

    public AuthService(UserRepository userRepository, JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.jwtUtil = jwtUtil;
    }

    public Map<String, String> signup(SignupRequest req) {
        String username = normalize(req.username());
        String password = req.password() == null ? "" : req.password();
        String name = normalize(req.name());
        String email = normalize(req.email());

        if (username.isBlank() || password.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "아이디와 비밀번호를 입력해 주세요.");
        }
        if (password.length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "비밀번호는 8자 이상 입력해 주세요.");
        }
        if (userRepository.existsByUsername(username)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이미 사용 중인 아이디입니다.");
        }

        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setName(name);
        user.setEmail(email);
        userRepository.save(user);
        return Map.of(
                "username", user.getUsername(),
                "message", "회원가입이 완료되었습니다. 로그인해 주세요."
        );
    }

    public Map<String, String> login(LoginRequest req) {
        String username = normalize(req.username());
        String password = req.password() == null ? "" : req.password();

        if (username.isBlank() || password.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "아이디와 비밀번호를 입력해 주세요.");
        }

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다."));
        if (!passwordMatches(password, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다.");
        }
        if (isLegacyHash(user.getPasswordHash())) {
            user.setPasswordHash(passwordEncoder.encode(password));
            userRepository.save(user);
        }
        return tokenResponse(user);
    }

    private Map<String, String> tokenResponse(User user) {
        return Map.of(
                "token", jwtUtil.generate(user.getId(), user.getUsername()),
                "username", user.getUsername(),
                "name", user.getName() == null ? "" : user.getName()
        );
    }

    private boolean passwordMatches(String password, String storedHash) {
        if (storedHash == null || storedHash.isBlank()) return false;
        if (storedHash.startsWith("$2")) return passwordEncoder.matches(password, storedHash);
        return isLegacyHash(storedHash) && storedHash.equals(legacyHash(password));
    }

    private boolean isLegacyHash(String value) {
        return value != null && value.matches("[0-9a-fA-F]{64}");
    }

    private String legacyHash(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(password.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
