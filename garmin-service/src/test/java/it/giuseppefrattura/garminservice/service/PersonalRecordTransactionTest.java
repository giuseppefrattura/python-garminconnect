package it.giuseppefrattura.garminservice.service;

import it.giuseppefrattura.garminservice.repository.ExercisePersonalRecordRepository;
import it.giuseppefrattura.garminservice.repository.StrengthWorkoutRepository;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PersonalRecordTransactionTest {
    @Test void rollbackPreservesExistingRecordsWhenAutomaticRecalculationFails() {
        try (var context = new AnnotationConfigApplicationContext(TestConfig.class)) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            jdbc.execute("CREATE TABLE records (id INT PRIMARY KEY)");
            jdbc.update("INSERT INTO records (id) VALUES (1)");
            var records = context.getBean(ExercisePersonalRecordRepository.class);
            var workouts = context.getBean(StrengthWorkoutRepository.class);
            when(records.findAllOrdered()).thenReturn(List.of());
            doAnswer(invocation -> { jdbc.update("DELETE FROM records"); return null; }).when(records).deleteAll();
            when(workouts.findAllByOrderByWorkoutDateDescWorkoutTimeDesc()).thenThrow(new IllegalStateException("failure"));

            var service = context.getBean(PersonalRecordService.class);
            assertThrows(IllegalStateException.class, service::getTrophyRoomCards);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM records", Integer.class));
            assertThrows(IllegalStateException.class, service::recalculateAllRecords);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM records", Integer.class));
        }
    }

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {
        @Bean DataSource dataSource() { return new DriverManagerDataSource("jdbc:h2:mem:records;DB_CLOSE_DELAY=-1", "sa", ""); }
        @Bean JdbcTemplate jdbcTemplate(DataSource dataSource) { return new JdbcTemplate(dataSource); }
        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) { return new DataSourceTransactionManager(dataSource); }
        @Bean ExercisePersonalRecordRepository records() { return mock(ExercisePersonalRecordRepository.class); }
        @Bean StrengthWorkoutRepository workouts() { return mock(StrengthWorkoutRepository.class); }
        @Bean PersonalRecordService service(ExercisePersonalRecordRepository records, StrengthWorkoutRepository workouts) {
            return new PersonalRecordService(records, workouts);
        }
    }
}
