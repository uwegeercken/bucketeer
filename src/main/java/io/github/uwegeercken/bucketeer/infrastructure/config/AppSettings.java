package io.github.uwegeercken.bucketeer.infrastructure.config;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

@Component
public class AppSettings {

    private static final Logger log = LoggerFactory.getLogger(AppSettings.class);
    private static final Path SETTINGS_PATH = Path.of(System.getProperty("user.home"), ".bucketeer", "settings.json");

    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT).build();
    private volatile int snapshotRetentionDays = 30;
    private volatile String timeZoneId = ZoneId.systemDefault().getId();

    public AppSettings() {
        load();
    }

    public int getSnapshotRetentionDays() {
        return snapshotRetentionDays;
    }

    public void setSnapshotRetentionDays(int days) {
        this.snapshotRetentionDays = days > 0 ? days : 30;
        save();
    }

    public String getTimeZoneId() {
        return timeZoneId;
    }

    /** Sets the time zone id; invalid or blank values fall back to the system default. */
    public void setTimeZoneId(String timeZoneId) {
        this.timeZoneId = timeZoneId != null && isValidZoneId(timeZoneId)
                ? timeZoneId.trim() : ZoneId.systemDefault().getId();
        save();
    }

    public Map<String, Object> toMap() {
        return Map.of(
                "snapshotRetentionDays", snapshotRetentionDays,
                "timeZoneId", timeZoneId);
    }

    private static boolean isValidZoneId(String id) {
        if (id == null || id.isBlank()) return false;
        try {
            ZoneId.of(id.trim());
            return true;
        } catch (java.time.DateTimeException e) {
            return false;
        }
    }

    private void load() {
        if (!Files.exists(SETTINGS_PATH)) return;
        try {
            Map<String, Object> data = mapper.readValue(SETTINGS_PATH.toFile(),
                    new tools.jackson.core.type.TypeReference<>() {});
            Object val = data.get("snapshotRetentionDays");
            if (val instanceof Number n) snapshotRetentionDays = n.intValue();
            Object tz = data.get("timeZoneId");
            if (tz instanceof String s) {
                timeZoneId = isValidZoneId(s) ? s.trim() : ZoneId.systemDefault().getId();
            }
        } catch (tools.jackson.core.JacksonException e) {
            log.error("Failed to load settings from {}: {}", SETTINGS_PATH, e.getMessage());
        }
    }

    private void save() {
        try {
            Files.createDirectories(SETTINGS_PATH.getParent());
            Map<String, Object> data = new HashMap<>();
            data.put("snapshotRetentionDays", snapshotRetentionDays);
            data.put("timeZoneId", timeZoneId);
            mapper.writeValue(SETTINGS_PATH.toFile(), data);
        } catch (IOException e) {
            log.error("Failed to save settings: {}", e.getMessage());        }
    }
}
