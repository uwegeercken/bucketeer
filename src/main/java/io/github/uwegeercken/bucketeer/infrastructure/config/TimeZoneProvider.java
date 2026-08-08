package io.github.uwegeercken.bucketeer.infrastructure.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.ZoneId;

/**
 * Resolves the configured display/filter time zone from {@link AppSettings}.
 * Falls back to the system default when the stored id is invalid.
 */
@Component
public class TimeZoneProvider {

    private static final Logger log = LoggerFactory.getLogger(TimeZoneProvider.class);

    private final AppSettings appSettings;

    public TimeZoneProvider(AppSettings appSettings) {
        this.appSettings = appSettings;
    }

    public ZoneId getZone() {
        String id = appSettings.getTimeZoneId();
        if (id == null || id.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(id.trim());
        } catch (java.time.DateTimeException e) {
            log.warn("Invalid time zone '{}' in settings, using system default", id);
            return ZoneId.systemDefault();
        }
    }
}
