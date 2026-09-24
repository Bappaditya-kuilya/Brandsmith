package com.brandsmith.api.session;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.brandsmith.api.budget.IpRateLimiter;

import jakarta.validation.Valid;

import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.NO_CONTENT;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private static final String COOKIE_NAME = "owner_token";

    private final SessionService service;
    private final IpRateLimiter rateLimiter;
    private final boolean cookieSecure;
    private final long ttlDays;

    public SessionController(SessionService service,
                             IpRateLimiter rateLimiter,
                             @Value("${brandsmith.cookies.secure:true}") boolean cookieSecure,
                             @Value("${brandsmith.session.ttl-days:30}") long ttlDays) {
        this.service = service;
        this.rateLimiter = rateLimiter;
        this.cookieSecure = cookieSecure;
        this.ttlDays = ttlDays;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateSessionRequest request,
                                                      @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor,
                                                      HttpServletRequest httpRequest,
                                                      HttpServletResponse response) {
        String ip = clientIp(forwardedFor, httpRequest);
        if (!rateLimiter.tryAcquire(ip)) {
            throw new ResponseStatusException(TOO_MANY_REQUESTS, "Too many requests, try again shortly");
        }
        SessionService.CreatedSession session = service.create(request.idea());
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, session.ownerToken())
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofDays(ttlDays))
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
        return ResponseEntity.status(CREATED).body(Map.of("id", session.id().toString(), "idea", session.idea()));
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable UUID id,
                                   @CookieValue(name = COOKIE_NAME, required = false) String token) {
        return service.get(id, token);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id,
                                       @CookieValue(name = COOKIE_NAME, required = false) String token) {
        service.delete(id, token);
        return ResponseEntity.status(NO_CONTENT).build();
    }

    static String clientIp(String forwardedFor, HttpServletRequest request) {
        // Only trust XFF from loopback/private hops (Cloudspaces proxy); otherwise a client can spoof buckets.
        String remote = request.getRemoteAddr();
        boolean trustedProxy = remote == null
                || remote.equals("127.0.0.1")
                || remote.equals("::1")
                || remote.startsWith("10.")
                || remote.startsWith("192.168.")
                || remote.matches("172\\.(1[6-9]|2\\d|3[01])\\..*")
                || remote.equals("172.18.0.1"); // docker bridge
        if (trustedProxy && forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].strip();
        }
        return remote;
    }
}
