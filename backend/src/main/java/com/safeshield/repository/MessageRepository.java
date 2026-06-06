package com.safeshield.repository;

import com.safeshield.model.Message;
import com.safeshield.model.Session;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {
    List<Message> findBySessionOrderByCreatedAtAsc(Session session);
    long countBySessionAndRole(Session session, String role);
}
