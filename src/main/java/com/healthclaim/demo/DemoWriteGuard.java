package com.healthclaim.demo;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Public demo only ({@code demo} profile). The demo's sign-in accounts are public,
 * so they may analyze, explain and review claims, but they must not change the data
 * every visitor shares: policies and their documents, users, new claims or their
 * facts. Those writes are allowed only for the operator accounts listed in
 * {@code app.demo.write-allowed-users} (used to seed and maintain the demo).
 */
@Component
@Profile("demo")
public class DemoWriteGuard implements HandlerInterceptor, WebMvcConfigurer {

    /** Write endpoints that change shared demo data. */
    static final List<String> GUARDED = List.of(
            "/api/v1/policies",
            "/api/v1/policies/**",
            "/api/v1/admin/**",
            "/api/v1/claims",
            "/api/v1/claims/*/extract");

    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final AntPathMatcher matcher = new AntPathMatcher();
    private final Set<String> allowedUsers;

    public DemoWriteGuard(@Value("${app.demo.write-allowed-users:operator}") String allowedUsers) {
        this.allowedUsers = Arrays.stream(allowedUsers.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns(GUARDED);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getDispatcherType() != DispatcherType.REQUEST
                || !WRITE_METHODS.contains(request.getMethod())
                || GUARDED.stream().noneMatch(p -> matcher.match(p, request.getRequestURI()))) {
            return true;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && allowedUsers.contains(auth.getName())) {
            return true;
        }
        throw new DemoReadOnlyException();
    }
}
