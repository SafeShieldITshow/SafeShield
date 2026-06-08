package com.safeshield.repository;

import com.safeshield.model.Session;
import com.safeshield.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SessionRepository extends JpaRepository<Session, Long> {
    List<Session> findByUserOrderByCreatedAtDesc(User user);
    List<Session> findTop80ByUserOrderByCreatedAtDesc(User user);
    long countByUser(User user);
}
