package com.safeshield.repository;

import com.safeshield.model.Message;
import com.safeshield.model.Session;
import com.safeshield.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {
    interface SessionMessageCount {
        Long getSessionId();
        long getMessageCount();
    }

    List<Message> findBySessionOrderByCreatedAtAsc(Session session);
    long countBySessionAndRole(Session session, String role);

    @Query("""
            select m.session.id as sessionId, count(m.id) as messageCount
            from Message m
            where m.session.user = :user and m.role = 'user'
            group by m.session.id
            """)
    List<SessionMessageCount> countUserMessagesByUser(@Param("user") User user);

    @Query("""
            select m
            from Message m
            where m.session.user = :user
              and m.role = 'user'
              and m.id in (
                  select max(m2.id)
                  from Message m2
                  where m2.session.user = :user and m2.role = 'user'
                  group by m2.session.id
              )
            """)
    List<Message> findLatestUserMessagesByUser(@Param("user") User user);

    @Query("""
            select m.session.id as sessionId, count(m.id) as messageCount
            from Message m
            where m.session.id in :sessionIds and m.role = 'user'
            group by m.session.id
            """)
    List<SessionMessageCount> countUserMessagesBySessionIds(@Param("sessionIds") List<Long> sessionIds);

    @Query("""
            select m
            from Message m
            where m.session.id in :sessionIds
              and m.role = 'user'
              and m.id in (
                  select max(m2.id)
                  from Message m2
                  where m2.session.id in :sessionIds and m2.role = 'user'
                  group by m2.session.id
              )
            """)
    List<Message> findLatestUserMessagesBySessionIds(@Param("sessionIds") List<Long> sessionIds);
}
