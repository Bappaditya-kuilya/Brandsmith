package com.brandsmith.api.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.brandsmith.api.audit.AuditService.SseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/sessions/{id}")
public class AuditController {

    private static final String COOKIE_NAME = "owner_token";

    private final AuditService service;
    private final ObjectMapper mapper;

    public AuditController(AuditService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @PostMapping(value = "/audit", produces = {MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<?> audit(@PathVariable UUID id,
                                   @CookieValue(name = COOKIE_NAME, required = false) String token,
                                   @RequestHeader(value = "Accept", required = false) String accept) {
        List<SseEvent> events = new ArrayList<>();
        AuditResponse response = service.run(id, token, events::add);
        if (accept != null && accept.contains(MediaType.APPLICATION_JSON_VALUE)) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(sse(events));
    }

    @PostMapping("/drift-check")
    public DriftResult driftCheck(@PathVariable UUID id,
                                  @CookieValue(name = COOKIE_NAME, required = false) String token,
                                  @Valid @RequestBody DriftRequest request) {
        return service.driftCheck(id, token, request.text(), request.assetType());
    }

    private String sse(List<SseEvent> events) {
        StringBuilder sb = new StringBuilder();
        for (SseEvent event : events) {
            sb.append("event: ").append(event.name()).append('\n');
            sb.append("data: ").append(toJson(event.data())).append("\n\n");
        }
        return sb.toString();
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize SSE payload", e);
        }
    }
}
