package com.waitless.review.infrastructure.adaptor.out.config.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import org.hibernate.SessionFactory;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class HibernateMetricsConfig {

    private final MeterRegistry meterRegistry;
    private final EntityManagerFactory entityManagerFactory;
    @PostConstruct
    public void bindHibernateMetrics() {
        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        sessionFactory.getStatistics().setStatisticsEnabled(true);

        meterRegistry.gauge("hibernate.session.open.count", sessionFactory.getStatistics(), stats -> (double) stats.getSessionOpenCount());
        meterRegistry.gauge("hibernate.query.execution.count", sessionFactory.getStatistics(), stats -> (double) stats.getQueryExecutionCount());
        meterRegistry.gauge("hibernate.transaction.count", sessionFactory.getStatistics(), stats -> (double) stats.getTransactionCount());
    }
}
