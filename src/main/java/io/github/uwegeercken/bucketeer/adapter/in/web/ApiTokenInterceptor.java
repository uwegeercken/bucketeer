package io.github.uwegeercken.bucketeer.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Optional bearer-token gate for the terminal REST API ({@code /api/v1/**}).
 * When {@code bucketeer.api-token} is empty (default), every request is allowed.
 * Otherwise the {@code Authorization: Bearer <token>} header must match the configured
 * token (constant-time comparison). The browser UI is not affected.
 */
@Component
public class ApiTokenInterceptor implements HandlerInterceptor {

    private final String token;

    public ApiTokenInterceptor(@Value("${bucketeer.api-token:}") String token) {
        this.token = token == null ? "" : token.trim();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (token.isEmpty()) {
            return true;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String provided = authorization.substring("Bearer ".length()).trim();
            if (MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                    provided.getBytes(StandardCharsets.UTF_8))) {
                return true;
            }
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"Unauthorized\"}");
        return false;
    }
}