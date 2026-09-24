package com.brandsmith.api.session;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SessionExpiryCleaner {

    private final JdbcTemplate jdbc;

    public SessionExpiryCleaner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "0 0 * * * *")
    public void deleteExpired() {
        jdbc.update("DELETE FROM session WHERE expires_at IS NOT NULL AND expires_at <= now()");
    }
}
