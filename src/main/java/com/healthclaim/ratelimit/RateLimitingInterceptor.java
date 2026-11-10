package com.healthclaim.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthclaim.common.response.ApiError;
import com.healthclaim.common.web.CorrelationIdFilter;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces a per-caller rate limit on the endpoints it is mapped to (the expensive
 * AI-backed ones). The caller key is the authenticated username, or the remote
 * address when anonymous. On breach it returns 429 with the standard error envelope.
 * Runs after authentication, so the principal is available.
 */
public class RateLimitingInterceptor implements HandlerInterceptor {

    private final RateLimiterRegistry registry;
    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;

    public RateLimitingInterceptor(RateLimiterRegistry registry, RateLimitProperties properties,
                                   ObjectMapper objectMapper) {
        this.registry = registry;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // An ASYNC dispatch (e.g. completing a streamed analysis) continues a request that
        // was already counted on its initial dispatch; counting it again would double-charge.
        if (!properties.enabled() || !HttpMethod.POST.matches(request.getMethod())
                || request.getDispatcherType() == DispatcherType.ASYNC) {
            return true;
        }
        RateLimiter limiter = registry.rateLimiter("ai:" + callerKey(request));
        if (limiter.acquirePermission()) {
            return true;
        }
        ApiError body = ApiError.of(HttpStatus.TOO_MANY_REQUESTS.value(), "RATE_LIMITED",
                "Too many requests; please slow down and retry later.", request.getRequestURI(),
                null, MDC.get(CorrelationIdFilter.TRACE_ID));
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
        return false;
    }

    private static String callerKey(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && StringUtils.hasText(auth.getName())
                && !"anonymousUser".equals(auth.getName())) {
            return auth.getName();
        }
        return request.getRemoteAddr();
    }
}
