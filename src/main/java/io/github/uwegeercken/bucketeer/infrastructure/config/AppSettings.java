package io.github.uwegeercken.bucketeer.infrastructure.config;

import io.github.uwegeercken.bucketeer.domain.template.LabelTemplate;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AppSettings {

    private static final Logger log = LoggerFactory.getLogger(AppSettings.class);

    /** Upper bound for the parallel listing workers; matches BucketeerService's hard cap. */
    public static final int MAX_QUERY_PARALLELISM = 32;

    /** Upper bound for the prefix-analysis samples drawn per listing scope. */
    public static final int MAX_QUERY_SAMPLE_SIZE = 64;

    /** Upper bound for the number of prefix variables (named reusable templates). */
    public static final int MAX_PREFIX_VARIABLES = 20;

    /** Sample count used while nothing is stored in the settings dialog. */
    public static final int DEFAULT_QUERY_SAMPLE_SIZE = 8;

    /** Stored value meaning "not set in the dialog - use the default". */
    private static final int QUERY_PARALLELISM_UNSET = -1;

    /** Stored value meaning "not set in the dialog - use {@link #DEFAULT_QUERY_SAMPLE_SIZE}". */
    private static final int QUERY_SAMPLE_SIZE_UNSET = -1;

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
    private volatile int querySampleSize = QUERY_SAMPLE_SIZE_UNSET;
    private volatile List<LabelTemplate> labelTemplates = List.of();

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
        this.timeZoneId = isValidZoneId(timeZoneId)
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

    /**
     * Number of sub prefixes sampled by the prefix analysis before a listing starts.
     * Returns the value stored from the settings dialog, or
     * {@link #DEFAULT_QUERY_SAMPLE_SIZE} while unset. The analysis decides between a
     * sequential and a parallel listing, so higher values buy accuracy at the cost of a
     * few more analysis requests per cache miss.
     */
    public int getQuerySampleSize() {
        return querySampleSize >= 0 ? querySampleSize : DEFAULT_QUERY_SAMPLE_SIZE;
    }

    /**
     * Stores the prefix-analysis sample count (1..64). A negative value resets the
     * setting to "not set", which makes {@link #DEFAULT_QUERY_SAMPLE_SIZE} apply again.
     */
    public void setQuerySampleSize(int value) {
        this.querySampleSize = value < 0
                ? QUERY_SAMPLE_SIZE_UNSET
                : Math.max(1, Math.min(value, MAX_QUERY_SAMPLE_SIZE));
        save();
    }

    public List<LabelTemplate> getLabelTemplates() {
        return labelTemplates;
    }

    /**
     * Stores the prefix variables (named reusable templates). Invalid entries (blank
     * or malformed names, blank templates, duplicates) are dropped, and the list is
     * capped at {@link #MAX_PREFIX_VARIABLES}.
     */
    public void setLabelTemplates(List<LabelTemplate> templates) {
        this.labelTemplates = normalizeLabelTemplates(templates);
        save();
    }

    private static List<LabelTemplate> normalizeLabelTemplates(List<LabelTemplate> templates) {
        if (templates == null || templates.isEmpty()) return List.of();
        Map<String, String> seen = new LinkedHashMap<>();
        for (LabelTemplate t : templates) {
            if (t == null) continue;
            String name = t.name() == null ? "" : t.name().trim();
            String template = t.template() == null ? "" : t.template().trim();
            if (name.isEmpty() || template.isEmpty() || !LabelTemplate.isValidName(name)) continue;
            if (seen.putIfAbsent(name, template) == null && seen.size() >= MAX_PREFIX_VARIABLES) break;
        }
        if (seen.isEmpty()) return List.of();
        return seen.entrySet().stream()
                .map(e -> new LabelTemplate(e.getKey(), e.getValue()))
                .toList();
    }

    private static List<Map<String, String>> labelTemplatesToMaps(List<LabelTemplate> templates) {
        return templates.stream()
                .map(t -> Map.of("name", t.name(), "template", t.template()))
                .toList();
    }

    public Map<String, Object> toMap() {
        return Map.of(
                "snapshotRetentionDays", snapshotRetentionDays,
                "timeZoneId", timeZoneId,
                "maxFileSizeMb", maxFileSizeMb,
                "maxRequestSizeMb", maxRequestSizeMb,
                "duckdbQuackEnabled", duckdbQuackEnabled,
                "queryParallelism", getQueryParallelism(),
                "querySampleSize", getQuerySampleSize(),
                "labelTemplates", labelTemplatesToMaps(labelTemplates));
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
            Object samples = data.get("querySampleSize");
            if (samples instanceof Number n) {
                int v = n.intValue();
                querySampleSize = v < 0 ? QUERY_SAMPLE_SIZE_UNSET
                        : Math.max(1, Math.min(v, MAX_QUERY_SAMPLE_SIZE));
            }
            Object variables = data.get("labelTemplates");
            if (variables instanceof List<?> list) {
                List<LabelTemplate> parsed = new ArrayList<>();
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        Object n = m.get("name");
                        Object t = m.get("template");
                        if (n instanceof String ns && t instanceof String ts) {
                            parsed.add(new LabelTemplate(ns, ts));
                        }
                    }
                }
                labelTemplates = normalizeLabelTemplates(parsed);
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
            // raw value: -1 means "not set in the dialog"
            data.put("querySampleSize", querySampleSize);
            data.put("labelTemplates", labelTemplatesToMaps(labelTemplates));
            mapper.writeValue(settingsPath.toFile(), data);
        } catch (IOException e) {
            log.error("Failed to save settings: {}", e.getMessage());        }
    }
}
