package com.brandsmith.api.interview;

import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.brandsmith.api.interview.S1InterviewService.InterviewResponse;

import jakarta.validation.Valid;

@RestController
public class InterviewController {

    private static final String COOKIE_NAME = "owner_token";

    private final S1InterviewService service;

    public InterviewController(S1InterviewService service) {
        this.service = service;
    }

    @PostMapping("/api/sessions/{id}/interview/answer")
    public InterviewResponse answer(@PathVariable UUID id,
                                    @CookieValue(name = COOKIE_NAME, required = false) String token,
                                    @Valid @RequestBody AnswerRequest request) {
        return service.submit(id, token, request);
    }

    @PatchMapping("/api/sessions/{id}/brief")
    public Map<String, Object> patchBrief(@PathVariable UUID id,
                                          @CookieValue(name = COOKIE_NAME, required = false) String token,
                                          @Valid @RequestBody PatchBriefRequest request) {
        return service.patch(id, token, request);
    }
}
