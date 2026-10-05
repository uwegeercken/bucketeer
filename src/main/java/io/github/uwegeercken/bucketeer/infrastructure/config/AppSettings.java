package io.github.uwegeercken.bucketeer.infrastructure.config;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    /** Upper bound for the parallel listing workers; matches BucketeerService's hard cap. */
    public static final int MAX_QUERY_PARALLELISM = 32;

    /** Stored value meaning "not set in the dialog - use the application.yml default". */
    private static final int QUERY_PARALLELISM_UNSET = -1;

    private final Path settingsPath;

    /** Default from {@code bucketeer.query.parallelism}, used while no dialog value is stored. */
    private final int configuredQueryParallelism;

    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT).build();
    private volatile int snapshotRetentionDays = 30;
    private volatile String timeZoneId = ZoneId.systemDefault().getId();
    private volatile int maxFileSizeMb = 100;
    private volatile int maxRequestSizeMb = 500;
    private volatile boolean duckdbQuackEnabled = false;
    private volatile int queryParallelism = QUERY_PARALLELISM_UNSET;

    @Autowired
    public AppSettings(S3Properties s3Properties) {
        this(Path.of(System.getProperty("user.home"), ".bucketeer", "settings.json"),
                s3Properties != null && s3Properties.query() != null
                        ? s3Properties.query().parallelism() : 0);
    }

    /** Package-private: settings file location injectable for tests. */
    AppSettings(Path settingsPath) {
        this(settingsPath, 0);
    }

    /** Package-private: settings file location and yml default injectable for tests. */
    AppSettings(Path settingsPath, int configuredQueryParallelism) {
        this.settingsPath = settingsPath;
        this.configuredQueryParallelism = configuredQueryParallelism;
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

    public int getMaxFileSizeMb() {
        return maxFileSizeMb;
    }

    /** Sets the maximum single-file size for uploads in MB (1..2048). */
    public void setMaxFileSizeMb(int mb) {
        this.maxFileSizeMb = mb >= 1 ? Math.min(mb, 2048) : 100;
        save();
    }

    public int getMaxRequestSizeMb() {
        return maxRequestSizeMb;
    }

    /** Sets the maximum total request size for uploads in MB (1..2048). */
    public void setMaxRequestSizeMb(int mb) {
        this.maxRequestSizeMb = mb >= 1 ? Math.min(mb, 2048) : 500;
        save();
    }

    public boolean isDuckdbQuackEnabled() {
        return duckdbQuackEnabled;
    }

    /** Enables/disables the DuckDB Quack remote SQL access (off by default). */
    public void setDuckdbQuackEnabled(boolean enabled) {
        this.duckdbQuackEnabled = enabled;
        save();
    }

    /** Sets the time zone id; invalid or blank values fall back to the system default. */
    public void setTimeZoneId(String timeZoneId) {
        this.timeZoneId = timeZoneId != null && isValidZoneId(timeZoneId)
                ? timeZoneId.trim() : ZoneId.systemDefault().getId();
        save();
    }

    /**
     * Effective number of parallel listing workers. Returns the value stored from the
     * settings dialog, or the {@code bucketeer.query.parallelism} default while unset.
     * 0 and 1 both mean "always sequential".
     */
    public int getQueryParallelism() {
        return queryParallelism >= 0 ? queryParallelism : configuredQueryParallelism;
    }

    /**
     * Stores the parallel listing worker count (0..32). A negative value resets the
     * setting to "not set", which makes the application.yml default apply again.
     */
    public void setQueryParallelism(int value) {
        this.queryParallelism = value < 0
                ? QUERY_PARALLELISM_UNSET
                : Math.min(value, MAX_QUERY_PARALLELISM);
        save();
    }

    public Map<String, Object> toMap() {
        return Map.of(
                "snapshotRetentionDays", snapshotRetentionDays,
                "timeZoneId", timeZoneId,
                "maxFileSizeMb", maxFileSizeMb,
                "maxRequestSizeMb", maxRequestSizeMb,
                "duckdbQuackEnabled", duckdbQuackEnabled,
                "queryParallelism", getQueryParallelism());
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

    private static int clipMb(int mb) {
        return mb >= 1 ? Math.min(mb, 2048) : 100;
    }

    private void load() {
        if (!Files.exists(settingsPath)) return;
        try {
            Map<String, Object> data = mapper.readValue(settingsPath.toFile(),
                    new tools.jackson.core.type.TypeReference<>() {});
            Object val = data.get("snapshotRetentionDays");
            if (val instanceof Number n) snapshotRetentionDays = n.intValue();
            Object tz = data.get("timeZoneId");
            if (tz instanceof String s) {
                timeZoneId = isValidZoneId(s) ? s.trim() : ZoneId.systemDefault().getId();
            }
            Object mfs = data.get("maxFileSizeMb");
            if (mfs instanceof Number n) maxFileSizeMb = clipMb(n.intValue());
            Object mrs = data.get("maxRequestSizeMb");
            if (mrs instanceof Number n) maxRequestSizeMb = clipMb(n.intValue());
            Object quack = data.get("duckdbQuackEnabled");
            if (quack instanceof Boolean b) duckdbQuackEnabled = b;
            Object par = data.get("queryParallelism");
            if (par instanceof Number n) {
                int v = n.intValue();
                queryParallelism = v < 0 ? QUERY_PARALLELISM_UNSET : Math.min(v, MAX_QUERY_PARALLELISM);
            }
        } catch (tools.jackson.core.JacksonException e) {
            log.error("Failed to load settings from {}: {}", settingsPath, e.getMessage());
        }
    }

    private void save() {
        try {
            Files.createDirectories(settingsPath.getParent());
            Map<String, Object> data = new HashMap<>();
            data.put("snapshotRetentionDays", snapshotRetentionDays);
            data.put("timeZoneId", timeZoneId);
            data.put("maxFileSizeMb", maxFileSizeMb);
            data.put("maxRequestSizeMb", maxRequestSizeMb);
            data.put("duckdbQuackEnabled", duckdbQuackEnabled);
            // raw value: -1 keeps the link to the application.yml default
            data.put("queryParallelism", queryParallelism);
            mapper.writeValue(settingsPath.toFile(), data);
        } catch (IOException e) {
            log.error("Failed to save settings: {}", e.getMessage());        }
    }
}
