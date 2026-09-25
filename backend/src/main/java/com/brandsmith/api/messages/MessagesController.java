package com.brandsmith.api.messages;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.brandsmith.api.messages.MessagesService.RunResult;
import com.brandsmith.api.messages.MessagesService.SseEvent;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/sessions/{id}/stages/messages")
public class MessagesController {

    private static final Logger log = LoggerFactory.getLogger(MessagesController.class);
    private static final String COOKIE_NAME = "owner_token";
    private static final String STAGE = "messages";
    private static final long SSE_TIMEOUT_MS = 120_000;

    private final MessagesService service;

    public MessagesController(MessagesService service) {
        this.service = service;
    }

    @PostMapping(value = "/run", headers = "Accept=application/json",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> run(@PathVariable UUID id,
                                 @CookieValue(name = COOKIE_NAME, required = false) String token) {
        return ResponseEntity.ok(service.run(id, token, null).output());
    }

    @PostMapping(value = "/run", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter runStream(@PathVariable UUID id,
                                @CookieValue(name = COOKIE_NAME, required = false) String token) {
        return stream(id, token, null);
    }

    @PostMapping(value = "/regenerate", headers = "Accept=application/json",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> regenerate(@PathVariable UUID id,
                                        @CookieValue(name = COOKIE_NAME, required = false) String token,
                                        @Valid @RequestBody(required = false) RegenerateRequest request) {
        return ResponseEntity.ok(service.run(id, token, note(request)).output());
    }

    @PostMapping(value = "/regenerate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter regenerateStream(@PathVariable UUID id,
                                       @CookieValue(name = COOKIE_NAME, required = false) String token,
                                       @Valid @RequestBody(required = false) RegenerateRequest request) {
        return stream(id, token, note(request));
    }

    @PostMapping("/select")
    public Map<String, Object> select(@PathVariable UUID id,
                                      @CookieValue(name = COOKIE_NAME, required = false) String token,
                                      @RequestBody SelectRequest request) {
        return service.select(id, token, request);
    }

    private static String note(RegenerateRequest request) {
        return request == null ? null : request.note();
    }

    private SseEmitter stream(UUID id, String token, String note) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        Thread.startVirtualThread(() -> {
            try {
                send(emitter, new SseEvent("stage_started", Map.of("stage", STAGE)));
                RunResult result = service.run(id, token, note, event -> send(emitter, event));
                send(emitter, new SseEvent("stage_completed", Map.of(
                        "stage", STAGE,
                        "status", result.degraded() ? "degraded" : "ok",
                        "latencyMs", result.latencyMs(),
                        "data", result.output())));
                emitter.complete();
            } catch (Exception e) {
                log.error("Messages SSE run failed", e);
                try {
                    send(emitter, new SseEvent("error", Map.of(
                            "stage", STAGE,
                            "message", "Messages failed")));
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
