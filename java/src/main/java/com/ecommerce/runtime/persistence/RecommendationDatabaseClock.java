package com.ecommerce.runtime.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Session;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** PostgreSQL transaction-start timestamps are unsafe after row-lock waits; use wall-clock DB time. */
final class RecommendationDatabaseClock {
    private static final Map<EntityManagerFactory, String> SQL = Collections.synchronizedMap(new WeakHashMap<>());

    private RecommendationDatabaseClock() {}

    static Instant now(EntityManager entityManager) {
        String query = SQL.computeIfAbsent(entityManager.getEntityManagerFactory(), ignored ->
                entityManager.unwrap(Session.class).doReturningWork(connection ->
                        "PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())
                                ? "select clock_timestamp()" : "select current_timestamp"));
        return entityManager.unwrap(Session.class).doReturningWork(connection -> {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(query)) {
                if (!rows.next()) throw new IllegalStateException("Database clock query returned no row");
                return rows.getTimestamp(1).toInstant();
            }
        });
    }
}
