package io.github.uwegeercken.bucketeer.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@Component
public class AppSettings {

    private static final Logger log = LoggerFactory.getLogger(AppSettings.class);
    private static final Path SETTINGS_PATH = Path.of(System.getProperty("user.home"), ".bucketeer", "settings.json");

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private volatile int snapshotRetentionDays = 30;

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

    public Map<String, Object> toMap() {
        return Map.of("snapshotRetentionDays", snapshotRetentionDays);
    }

    private void load() {
        if (!Files.exists(SETTINGS_PATH)) return;
        try {
            Map<String, Object> data = mapper.readValue(SETTINGS_PATH.toFile(),
                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
            Object val = data.get("snapshotRetentionDays");
            if (val instanceof Number n) snapshotRetentionDays = n.intValue();
        } catch (IOException e) {
            log.warn("Failed to load settings from {}: {}", SETTINGS_PATH, e.getMessage());
        }
    }

    private void save() {
        try {
            Files.createDirectories(SETTINGS_PATH.getParent());
            Map<String, Object> data = new HashMap<>();
            data.put("snapshotRetentionDays", snapshotRetentionDays);
            mapper.writeValue(SETTINGS_PATH.toFile(), data);
        } catch (IOException e) {
            log.error("Failed to save settings: {}", e.getMessage());
        }
    }
}
