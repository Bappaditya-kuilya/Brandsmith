package com.brandsmith.api.audit;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.brandsmith.api.audit.AuditService.SseEvent;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/sessions/{id}")
public class AuditController {

    private static final Logger log = LoggerFactory.getLogger(AuditController.class);
    private static final String COOKIE_NAME = "owner_token";
    private static final long SSE_TIMEOUT_MS = 120_000;

    private final AuditService service;

    public AuditController(AuditService service) {
        this.service = service;
    }

    @PostMapping(value = "/audit", headers = "Accept=application/json",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public AuditResponse audit(@PathVariable UUID id,
                               @CookieValue(name = COOKIE_NAME, required = false) String token) {
        return service.run(id, token, event -> {
        });
    }

    @PostMapping(value = "/audit", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter auditStream(@PathVariable UUID id,
                                  @CookieValue(name = COOKIE_NAME, required = false) String token) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        Thread.startVirtualThread(() -> {
            try {
                service.run(id, token, event -> send(emitter, event));
                emitter.complete();
            } catch (Exception e) {
                log.error("Audit SSE run failed", e);
                try {
                    send(emitter, new SseEvent("error", Map.of(
                            "stage", AuditService.STAGE,
                            "message", "Audit failed")));
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

    @PostMapping("/drift-check")
    public DriftResult driftCheck(@PathVariable UUID id,
                                  @CookieValue(name = COOKIE_NAME, required = false) String token,
                                  @Valid @RequestBody DriftRequest request) {
        return service.driftCheck(id, token, request.text(), request.assetType());
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
