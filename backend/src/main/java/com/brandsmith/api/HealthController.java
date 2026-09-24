package com.brandsmith.api;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("db", dbStatus());
        return body;
    }

    private String dbStatus() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(2) ? "up" : "down";
        } catch (SQLException e) {
            return "down";
        }
    }
}
