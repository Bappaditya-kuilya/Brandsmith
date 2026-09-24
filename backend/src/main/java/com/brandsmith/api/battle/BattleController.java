package com.brandsmith.api.battle;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.brandsmith.api.battle.BattleService.SseEvent;

import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/sessions/{id}/stages/position")
public class BattleController {

    private static final Logger log = LoggerFactory.getLogger(BattleController.class);
    private static final String COOKIE_NAME = "owner_token";
    private static final long SSE_TIMEOUT_MS = 120_000;

    private final BattleService service;

    public BattleController(BattleService service) {
        this.service = service;
    }

    @PostMapping(value = "/run", params = "sync=1")
    public BattleResult runSync(@PathVariable UUID id,
                                @CookieValue(name = COOKIE_NAME, required = false) String token,
                                @RequestParam(name = "note", required = false) String note) {
        return service.run(id, token, note, event -> {
        });
    }

    @PostMapping(value = "/run", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter runStream(@PathVariable UUID id,
                                @CookieValue(name = COOKIE_NAME, required = false) String token,
                                @RequestParam(name = "note", required = false) String note) {
        service.precheck(id, token, note);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        Thread.startVirtualThread(() -> {
            try {
                service.run(id, token, note, event -> send(emitter, event));
                emitter.complete();
            } catch (Exception e) {
                log.error("Positioning Battle SSE run failed", e);
                try {
                    send(emitter, new SseEvent("error", Map.of(
                            "stage", BattleService.STAGE,
                            "message", "Positioning Battle failed")));
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

    @PostMapping("/select")
    public Map<String, Object> select(@PathVariable UUID id,
                                      @CookieValue(name = COOKIE_NAME, required = false) String token,
                                      @RequestBody SelectRequest request) {
        return service.select(id, token, request);
    }

    @PostMapping("/regenerate")
    public BattleResult regenerate(@PathVariable UUID id,
                                   @CookieValue(name = COOKIE_NAME, required = false) String token,
                                   @Valid @RequestBody(required = false) RegenerateRequest request) {
        return service.run(id, token, request == null ? null : request.note(), event -> {
        });
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
