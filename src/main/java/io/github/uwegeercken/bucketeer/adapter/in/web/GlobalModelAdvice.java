package io.github.uwegeercken.bucketeer.adapter.in.web;

import io.github.uwegeercken.bucketeer.domain.port.in.BucketeerUseCase;
import io.github.uwegeercken.bucketeer.infrastructure.config.S3Properties;
import io.github.uwegeercken.bucketeer.infrastructure.config.TimeZoneProvider;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.List;

@ControllerAdvice
public class GlobalModelAdvice {

    private static final Logger log = LoggerFactory.getLogger(GlobalModelAdvice.class);

    private final BucketeerUseCase bucketeerUseCase;
    private final SessionContext sessionContext;
    private final S3Properties s3Properties;
    private final TimeZoneProvider timeZoneProvider;

    public GlobalModelAdvice(BucketeerUseCase bucketeerUseCase,
                             SessionContext sessionContext,
                             S3Properties s3Properties,
                             TimeZoneProvider timeZoneProvider) {
        this.bucketeerUseCase  = bucketeerUseCase;
        this.sessionContext    = sessionContext;
        this.s3Properties      = s3Properties;
        this.timeZoneProvider  = timeZoneProvider;
    }

    @ModelAttribute("serverNames")
    public List<String> serverNames() {
        try {
            return bucketeerUseCase.serverNames();
        } catch (Exception e) {
            log.error("Failed to load server names: {}", e.getMessage());
            return List.of();
        }
    }

    @ModelAttribute("selectedServer")
    public String selectedServer(HttpServletRequest request) {
        // /api/v1/** is the stateless terminal API: touching the session-scoped
        // SessionContext would create an HTTP session (JSESSIONID) on every call.
        String uri = request.getRequestURI();
        if (uri != null && uri.startsWith("/api/v1/")) {
            return null;
        }
        try {
            List<String> names = bucketeerUseCase.serverNames();
            String selected = names.isEmpty() ? null : sessionContext.getSelectedServer();
            // Also falls back when the selected server was deleted in the meantime.
            if (selected == null || !names.contains(selected)) {
                sessionContext.setSelectedServer(names.isEmpty() ? null : names.getFirst());
            }
        } catch (Exception e) {
            log.error("Failed to determine selected server: {}", e.getMessage());
        }
        return sessionContext.getSelectedServer();
    }

    @ModelAttribute("appVersion")
    public String appVersion() {
        return s3Properties.version();
    }

    @ModelAttribute("appReleaseDate")
    public String appReleaseDate() {
        return s3Properties.releaseDate();
    }

    @ModelAttribute("timeZoneId")
    public String timeZoneId() {
        return timeZoneProvider.getZone().getId();
    }
}