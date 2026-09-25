package com.brandsmith.api.visual;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.brandsmith.api.visual.VisualService.SseEvent;

import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/sessions/{id}")
public class VisualController {

    private static final Logger log = LoggerFactory.getLogger(VisualController.class);
    private static final String COOKIE_NAME = "owner_token";
    private static final String STAGE = "visual";
    private static final long SSE_TIMEOUT_MS = 120_000;

    private final VisualService service;

    public VisualController(VisualService service) {
        this.service = service;
    }

    @PostMapping(value = "/stages/visual/run", headers = "Accept=application/json",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> run(@PathVariable UUID id,
                                                   @CookieValue(name = COOKIE_NAME, required = false) String token) {
        return ResponseEntity.ok(service.run(id, token, null).toMap());
    }

    @PostMapping(value = "/stages/visual/run", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter runStream(@PathVariable UUID id,
                                @CookieValue(name = COOKIE_NAME, required = false) String token) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        Thread.startVirtualThread(() -> {
            try {
                send(emitter, new SseEvent("stage_started", Map.of("stage", STAGE)));
                VisualBoard board = service.run(id, token, null, event -> send(emitter, event));
                send(emitter, new SseEvent("stage_completed", Map.of(
                        "stage", STAGE,
                        "status", "ok",
                        "data", board.toMap())));
                emitter.complete();
            } catch (Exception e) {
                log.error("Visual SSE run failed", e);
                try {
                    send(emitter, new SseEvent("error", Map.of("message", "Visual failed")));
                } catch (RuntimeException ignored) {
                    // client already disconnected
                }
                try {
                    emitter.complete();
                } catch (RuntimeException ignored) {
                    // already completed
                }
            }
        });
        return emitter;
    }

    @PatchMapping(value = "/visual/tokens", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> patch(@PathVariable UUID id,
                                     @CookieValue(name = COOKIE_NAME, required = false) String token,
                                     @Valid @RequestBody(required = false) PatchTokensRequest request) {
        if (request == null) {
            request = new PatchTokensRequest(null, null, null);
        }
        return service.patch(id, token, request).toMap();
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
