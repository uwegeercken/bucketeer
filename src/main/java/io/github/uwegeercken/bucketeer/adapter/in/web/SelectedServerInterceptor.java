package io.github.uwegeercken.bucketeer.adapter.in.web;

import io.github.uwegeercken.bucketeer.domain.port.in.BucketeerUseCase;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Keeps the selected S3 server across session expiry and app restarts.
 *
 * <p>The selection lives in the {@link SessionContext}, which is lost after the servlet session
 * times out (Tomcat default: 30 min idle) or the app restarts - after which
 * {@link GlobalModelAdvice} would silently fall back to the first configured server. This
 * interceptor mirrors the selection into a long-lived cookie and restores it into a fresh session.
 */
@Component
public class SelectedServerInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SelectedServerInterceptor.class);

    static final String COOKIE_NAME = "bucketeer_server";
    private static final Duration COOKIE_MAX_AGE = Duration.ofDays(365);

    private final SessionContext sessionContext;
    private final BucketeerUseCase bucketeerUseCase;

    public SelectedServerInterceptor(SessionContext sessionContext, BucketeerUseCase bucketeerUseCase) {
        this.sessionContext   = sessionContext;
        this.bucketeerUseCase = bucketeerUseCase;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        try {
            String cookieValue = readCookie(request);
            String selected    = sessionContext.getSelectedServer();

            if (selected == null && cookieValue != null
                    && bucketeerUseCase.serverNames().contains(cookieValue)) {
                sessionContext.setSelectedServer(cookieValue);
                selected = cookieValue;
            }

            if (selected != null && !selected.equals(cookieValue)) {
                writeCookie(response, selected);
            }
        } catch (Exception e) {
            log.warn("Could not sync selected server with cookie: {}", e.getMessage());
        }
        return true;
    }

    /** Writes the selected-server cookie; also used directly when the user switches servers. */
    static void writeCookie(HttpServletResponse response, String serverName) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME,
                        URLEncoder.encode(serverName, StandardCharsets.UTF_8))
                .path("/")
                .maxAge(COOKIE_MAX_AGE)
                .httpOnly(true)
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private static String readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (COOKIE_NAME.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                return URLDecoder.decode(c.getValue(), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
