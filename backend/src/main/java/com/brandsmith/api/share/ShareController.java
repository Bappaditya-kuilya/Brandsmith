package com.brandsmith.api.share;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ShareController {

    private static final String COOKIE_NAME = "owner_token";

    private final ShareService service;

    public ShareController(ShareService service) {
        this.service = service;
    }

    @PostMapping("/sessions/{id}/share")
    public ShareService.CreatedShare create(@PathVariable UUID id,
                                            @CookieValue(name = COOKIE_NAME, required = false) String token) {
        return service.create(id, token);
    }

    @PostMapping("/sessions/{id}/export")
    public ResponseEntity<String> export(@PathVariable UUID id,
                                         @CookieValue(name = COOKIE_NAME, required = false) String token,
                                         @RequestParam(name = "format", required = false) String format) {
        ShareService.Export export = service.export(id, token, format);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + export.filename() + "\"")
                .contentType(MediaType.parseMediaType(export.contentType()))
                .body(export.body());
    }

    @GetMapping("/share/{token}")
    public Map<String, Object> publicKit(@PathVariable String token) {
        return service.resolve(token);
    }
}
