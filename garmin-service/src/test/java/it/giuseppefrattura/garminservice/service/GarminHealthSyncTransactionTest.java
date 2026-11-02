package it.giuseppefrattura.garminservice.service;

import it.giuseppefrattura.garminservice.client.GarminProxyClient;
import it.giuseppefrattura.garminservice.model.DailyHealthMetric;
import it.giuseppefrattura.garminservice.repository.DailyHealthMetricRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GarminHealthSyncTransactionTest {
    @Test
    void rollsBackOnlyTheFailedDayAndCanRetryWithoutDuplicates() {
        try (var context = new AnnotationConfigApplicationContext(TestConfig.class)) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            jdbc.execute("CREATE TABLE daily_metric (metric_date DATE PRIMARY KEY)");
            var repository = context.getBean(DailyHealthMetricRepository.class);
            LocalDate failedDate = LocalDate.now().minusDays(1);
            when(repository.findByMetricDate(any())).thenAnswer(invocation -> {
                LocalDate date = invocation.getArgument(0);
                int rows = jdbc.queryForObject("SELECT COUNT(*) FROM daily_metric WHERE metric_date = ?", Integer.class, Date.valueOf(date));
                if (rows == 0) return Optional.empty();
                DailyHealthMetric metric = new DailyHealthMetric();
                metric.setMetricDate(date);
                return Optional.of(metric);
            });
            when(repository.save(any(DailyHealthMetric.class))).thenAnswer(invocation -> {
                DailyHealthMetric metric = invocation.getArgument(0);
                jdbc.update("MERGE INTO daily_metric (metric_date) KEY (metric_date) VALUES (?)", Date.valueOf(metric.getMetricDate()));
                if (metric.getMetricDate().equals(failedDate)) throw new IllegalStateException("simulated failure");
                return metric;
            });

            Map<String, Object> result = context.getBean(GarminHealthSyncService.class).syncRecentHealthMetrics(3);
            assertEquals(2, result.get("syncedDays"));
            assertEquals(1, result.get("errorDays"));
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM daily_metric", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM daily_metric WHERE metric_date = ?", Integer.class, Date.valueOf(failedDate)));

            when(repository.save(any(DailyHealthMetric.class))).thenAnswer(invocation -> {
                DailyHealthMetric metric = invocation.getArgument(0);
                jdbc.update("MERGE INTO daily_metric (metric_date) KEY (metric_date) VALUES (?)", Date.valueOf(metric.getMetricDate()));
                return metric;
            });
            result = context.getBean(GarminHealthSyncService.class).syncRecentHealthMetrics(3);
            assertEquals(3, result.get("syncedDays"));
            assertEquals(0, result.get("errorDays"));
            assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM daily_metric", Integer.class));
        }
    }

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {
        @Bean DataSource dataSource() { return new DriverManagerDataSource("jdbc:h2:mem:healthsync;DB_CLOSE_DELAY=-1", "sa", ""); }
        @Bean JdbcTemplate jdbcTemplate(DataSource dataSource) { return new JdbcTemplate(dataSource); }
        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) { return new DataSourceTransactionManager(dataSource); }
        @Bean DailyHealthMetricRepository repository() { return mock(DailyHealthMetricRepository.class); }
        @Bean GarminProxyClient proxyClient() {
            GarminProxyClient client = mock(GarminProxyClient.class);
            when(client.getDailyHealthSummary(any())).thenReturn(Map.of());
            return client;
        }
        @Bean ReadinessCalculationService readiness() {
            ReadinessCalculationService readiness = mock(ReadinessCalculationService.class);
            when(readiness.calculateReadiness(any())).thenReturn(new ReadinessCalculationService.ReadinessResult(50, "MODERATE", "test"));
            return readiness;
        }
        @Bean DailyHealthMetricPersistenceService writer(DailyHealthMetricRepository repository, ReadinessCalculationService readiness) {
            return new DailyHealthMetricPersistenceService(repository, readiness);
        }
        @Bean GarminHealthSyncService service(GarminProxyClient client, DailyHealthMetricPersistenceService writer) {
            return new GarminHealthSyncService(client, writer);
        }
    }
}
