package com.ecommerce.runtime.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface RecommendationOutboxRepository extends JpaRepository<RecommendationOutboxEntity, String> {
    boolean existsByDedupKey(String dedupKey);

    @Query("select event.id from RecommendationOutboxEntity event "
            + "where event.attemptCount < :maxAttempts and "
            + "((event.status = 'PENDING' and (event.nextAttemptAt is null or event.nextAttemptAt <= :now)) "
            + "or (event.status = 'IN_FLIGHT' and event.leaseUntil <= :now)) "
            + "order by event.createdAt asc, event.id asc")
    List<String> findDispatchableIds(
            @Param("now") Instant now,
            @Param("maxAttempts") int maxAttempts,
            Pageable pageable);

    /** Repeating the eligibility predicate makes concurrent claim attempts atomic. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RecommendationOutboxEntity event set event.status = 'IN_FLIGHT', "
            + "event.claimToken = :token, event.leaseUntil = :leaseUntil, "
            + "event.attemptCount = event.attemptCount + 1, event.nextAttemptAt = null "
            + "where event.id = :id and event.attemptCount < :maxAttempts and "
            + "((event.status = 'PENDING' and (event.nextAttemptAt is null or event.nextAttemptAt <= :now)) "
            + "or (event.status = 'IN_FLIGHT' and event.leaseUntil <= :now))")
    int claim(@Param("id") String id, @Param("token") String token,
              @Param("now") Instant now, @Param("leaseUntil") Instant leaseUntil,
              @Param("maxAttempts") int maxAttempts);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RecommendationOutboxEntity event set event.status = 'PUBLISHED', "
            + "event.publishedAt = :now, event.lastError = null, event.claimToken = null, event.leaseUntil = null "
            + "where event.id = :id and event.status = 'IN_FLIGHT' and event.claimToken = :token "
            + "and event.leaseUntil > :now")
    int acknowledge(@Param("id") String id, @Param("token") String token, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RecommendationOutboxEntity event set event.status = :status, "
            + "event.nextAttemptAt = :nextAttemptAt, event.lastError = :error, "
            + "event.claimToken = null, event.leaseUntil = null "
            + "where event.id = :id and event.status = 'IN_FLIGHT' and event.claimToken = :token "
            + "and event.leaseUntil > :now")
    int fail(@Param("id") String id, @Param("token") String token, @Param("now") Instant now,
             @Param("status") String status, @Param("nextAttemptAt") Instant nextAttemptAt,
             @Param("error") String error);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RecommendationOutboxEntity event set event.status = 'DEAD_LETTER', "
            + "event.lastError = 'DELIVERY_LEASE_EXHAUSTED_OUTCOME_UNKNOWN', "
            + "event.claimToken = null, event.leaseUntil = null, event.nextAttemptAt = null "
            + "where event.attemptCount >= :maxAttempts and "
            + "((event.status = 'IN_FLIGHT' and event.leaseUntil <= :now) or event.status = 'PENDING')")
    int deadLetterExhausted(@Param("now") Instant now, @Param("maxAttempts") int maxAttempts);

    /** Active backlog excludes dead letters; they have a separate count and require operator reconciliation. */
    @Query("select coalesce(sum(case when event.status = 'PENDING' then 1 else 0 end), 0) as pending, "
            + "coalesce(sum(case when event.status = 'PUBLISHED' then 1 else 0 end), 0) as published, "
            + "coalesce(sum(case when event.status = 'DEAD_LETTER' then 1 else 0 end), 0) as deadLetter, "
            + "coalesce(sum(case when event.status = 'IN_FLIGHT' then 1 else 0 end), 0) as inFlight, "
            + "coalesce(sum(case when event.status = 'IN_FLIGHT' and event.leaseUntil <= :now then 1 else 0 end), 0) as expiredLeases, "
            + "min(case when event.status in ('PENDING', 'IN_FLIGHT') then event.createdAt else null end) as oldestUnfinishedCreatedAt "
            + "from RecommendationOutboxEntity event")
    BacklogSummary summarizeBacklog(@Param("now") Instant now);

    interface BacklogSummary {
        long getPending();
        long getPublished();
        long getDeadLetter();
        long getInFlight();
        long getExpiredLeases();
        Instant getOldestUnfinishedCreatedAt();
    }

    long countByStatus(String status);
}
