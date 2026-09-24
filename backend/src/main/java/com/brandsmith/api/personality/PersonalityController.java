package com.brandsmith.api.personality;

import java.util.Map;
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

import com.brandsmith.api.personality.PersonalityService.RunResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/sessions/{id}/stages/personality")
public class PersonalityController {

    private static final String COOKIE_NAME = "owner_token";
    private static final String STAGE = "personality";

    private final PersonalityService service;
    private final ObjectMapper mapper;

    public PersonalityController(PersonalityService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @PostMapping(value = "/run", produces = {MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<?> run(@PathVariable UUID id,
                                 @CookieValue(name = COOKIE_NAME, required = false) String token,
                                 @RequestHeader(value = "Accept", required = false) String accept) {
        return respond(service.run(id, token, null), accept);
    }

    @PostMapping(value = "/regenerate", produces = {MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<?> regenerate(@PathVariable UUID id,
                                        @CookieValue(name = COOKIE_NAME, required = false) String token,
                                        @RequestHeader(value = "Accept", required = false) String accept,
                                        @Valid @RequestBody(required = false) RegenerateRequest request) {
        String note = request == null ? null : request.note();
        return respond(service.run(id, token, note), accept);
    }

    private ResponseEntity<?> respond(RunResult result, String accept) {
        if (accept != null && accept.contains(MediaType.APPLICATION_JSON_VALUE)) {
            return ResponseEntity.ok(result.output());
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(sse(result));
    }

    private String sse(RunResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("event: stage_started\n");
        sb.append("data: {\"stage\":\"").append(STAGE).append("\"}\n\n");
        sb.append("event: progress\n");
        sb.append("data: {\"stage\":\"").append(STAGE)
                .append("\",\"message\":\"Generating traits and voice spec...\"}\n\n");
        Map<String, Object> completed = Map.of(
                "stage", STAGE,
                "status", result.degraded() ? "degraded" : "ok",
                "latencyMs", result.latencyMs(),
                "data", result.output());
        sb.append("event: stage_completed\n");
        sb.append("data: ").append(toJson(completed)).append("\n\n");
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
