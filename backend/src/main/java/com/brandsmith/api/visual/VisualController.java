package com.brandsmith.api.visual;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/sessions/{id}")
public class VisualController {

    private static final String COOKIE_NAME = "owner_token";
    private static final String STAGE = "visual";

    private final VisualService service;
    private final ObjectMapper mapper;

    public VisualController(VisualService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @PostMapping(value = "/stages/visual/run",
            produces = {MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<?> run(@PathVariable UUID id,
                                 @CookieValue(name = COOKIE_NAME, required = false) String token,
                                 @RequestHeader(value = "Accept", required = false) String accept) {
        return respond(service.run(id, token, null), accept);
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

    private ResponseEntity<?> respond(VisualBoard board, String accept) {
        Map<String, Object> data = board.toMap();
        if (accept != null && accept.contains(MediaType.APPLICATION_JSON_VALUE)) {
            return ResponseEntity.ok(data);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(sse(data));
    }

    private String sse(Map<String, Object> data) {
        StringBuilder sb = new StringBuilder();
        sb.append("event: stage_started\n");
        sb.append("data: {\"stage\":\"").append(STAGE).append("\"}\n\n");
        sb.append("event: progress\n");
        sb.append("data: {\"stage\":\"").append(STAGE)
                .append("\",\"message\":\"Building palette and logo...\"}\n\n");
        sb.append("event: stage_completed\n");
        sb.append("data: {\"stage\":\"").append(STAGE).append("\",\"status\":\"ok\",\"data\":")
                .append(toJson(data)).append("}\n\n");
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
