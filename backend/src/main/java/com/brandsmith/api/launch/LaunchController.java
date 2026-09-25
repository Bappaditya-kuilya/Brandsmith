package com.brandsmith.api.launch;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.brandsmith.api.launch.LaunchService.RunResult;
import com.brandsmith.api.launch.LaunchService.SseEvent;

@RestController
@RequestMapping("/api/sessions/{id}/stages/launch")
public class LaunchController {

    private static final Logger log = LoggerFactory.getLogger(LaunchController.class);
    private static final String COOKIE_NAME = "owner_token";
    private static final String STAGE = "launch";
    private static final long SSE_TIMEOUT_MS = 120_000;

    private final LaunchService service;

    public LaunchController(LaunchService service) {
        this.service = service;
    }

    @PostMapping(value = "/run", headers = "Accept=application/json",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public LaunchOutput run(@PathVariable UUID id,
                            @CookieValue(name = COOKIE_NAME, required = false) String token) {
        return service.run(id, token, null).output();
    }

    @PostMapping(value = "/run", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter runStream(@PathVariable UUID id,
                                @CookieValue(name = COOKIE_NAME, required = false) String token) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        Thread.startVirtualThread(() -> {
            try {
                send(emitter, new SseEvent("stage_started", Map.of("stage", STAGE)));
                RunResult result = service.run(id, token, null, event -> send(emitter, event));
                send(emitter, new SseEvent("stage_completed", Map.of(
                        "stage", STAGE,
                        "status", result.degraded() ? "degraded" : "ok",
                        "latencyMs", result.latencyMs(),
                        "data", result.output())));
                emitter.complete();
            } catch (Exception e) {
                log.error("Launch SSE run failed", e);
                try {
                    send(emitter, new SseEvent("error", Map.of(
                            "stage", STAGE,
                            "message", "Launch failed")));
                } catch (RuntimeException ignored) {
                }
                try {
                    emitter.complete();
                } catch (RuntimeException ignored) {
                }
            }
        });
        return emitter;
    }

    private void send(SseEmitter emitter, SseEvent event) {
        synchronized (emitter) {
            try {
                emitter.send(SseEmitter.event().name(event.name()).data(event.data()));
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
    }
}
