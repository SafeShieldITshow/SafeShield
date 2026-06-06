package com.safeshield.repository;

import com.safeshield.model.Report;
import com.safeshield.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ReportRepository extends JpaRepository<Report, Long> {
    List<Report> findByUserOrderByCreatedAtDesc(User user);
    Optional<Report> findFirstByUserOrderByCreatedAtDesc(User user);
    long countByUser(User user);
}
