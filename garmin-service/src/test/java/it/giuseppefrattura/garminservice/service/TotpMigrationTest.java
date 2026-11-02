package it.giuseppefrattura.garminservice.service;

import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TotpMigrationTest {
    @Test void existingAccountsKeepLegacyAlgorithm() throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:totpmigration;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, totp_secret VARCHAR(64))");
        jdbc.update("INSERT INTO users (id, totp_secret) VALUES (1, 'old-secret')");
        var liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:db/changelog/migrations/010-add-totp-algorithm.yaml");
        liquibase.afterPropertiesSet();
        assertEquals("SHA1", jdbc.queryForObject("SELECT totp_algorithm FROM users WHERE id = 1", String.class));
        assertEquals("old-secret", jdbc.queryForObject("SELECT totp_secret FROM users WHERE id = 1", String.class));
    }
}
