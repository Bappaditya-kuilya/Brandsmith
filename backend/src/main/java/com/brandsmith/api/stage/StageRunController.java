package com.brandsmith.api.stage;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.brandsmith.api.session.SessionService;
import com.brandsmith.api.stage.StageRunRepository.StageRunView;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@RestController
public class StageRunController {

    private static final String COOKIE_NAME = "owner_token";

    private final StageRunRepository repository;
    private final SessionService sessions;

    public StageRunController(StageRunRepository repository, SessionService sessions) {
        this.repository = repository;
        this.sessions = sessions;
    }

    @GetMapping("/api/sessions/{id}/stage-runs")
    public List<StageRunView> list(@PathVariable UUID id,
                                   @CookieValue(name = COOKIE_NAME, required = false) String token) {
        sessions.requireOwner(id, token);
        return repository.list(id);
    }

    @PostMapping("/api/sessions/{id}/stages/{stage}/lock")
    public Map<String, Object> lock(@PathVariable UUID id,
                                    @PathVariable String stage,
                                    @CookieValue(name = COOKIE_NAME, required = false) String token,
                                    @Valid @RequestBody LockRequest request) {
        sessions.requireOwner(id, token);
        String normalized = stage.toUpperCase(Locale.ROOT);
        if (!StageRunRepository.ORDER.contains(normalized)) {
            throw new ResponseStatusException(BAD_REQUEST, "Unknown stage: " + stage);
        }
        repository.setLocked(id, normalized, request.locked());
        return Map.of("stage", normalized, "locked", request.locked());
    }

    public record LockRequest(@NotNull Boolean locked) {
    }
}
