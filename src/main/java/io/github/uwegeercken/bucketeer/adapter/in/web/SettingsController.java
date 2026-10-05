package io.github.uwegeercken.bucketeer.adapter.in.web;

import io.github.uwegeercken.bucketeer.infrastructure.config.AppSettings;
import io.github.uwegeercken.bucketeer.infrastructure.db.DuckDbRepository;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
public class SettingsController {

    private static final List<String> COMMON_ZONES = List.of(
            "UTC",
            "Europe/Berlin",
            "Europe/London",
            "Europe/Paris",
            "Europe/Vienna",
            "Europe/Zurich",
            "America/New_York",
            "America/Chicago",
            "America/Los_Angeles",
            "America/Sao_Paulo",
            "Asia/Tokyo",
            "Asia/Singapore",
            "Asia/Shanghai",
            "Australia/Sydney"
    );

    private final AppSettings appSettings;

    private final DuckDbRepository duckDbRepository;

    public SettingsController(AppSettings appSettings, DuckDbRepository duckDbRepository) {
        this.appSettings = appSettings;
        this.duckDbRepository = duckDbRepository;
    }

    @GetMapping(value = "/api/settings", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> getSettings() {
        Map<String, Object> body = new LinkedHashMap<>(appSettings.toMap());
        body.put("systemTimeZoneId", ZoneId.systemDefault().getId());
        body.put("timeZoneOptions", COMMON_ZONES);
        return body;
    }

    @PostMapping(value = "/api/settings", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> saveSettings(@RequestBody Map<String, Object> body) {
        if (body.containsKey("snapshotRetentionDays")) {
            Object val = body.get("snapshotRetentionDays");
            if (val instanceof Number n) {
                appSettings.setSnapshotRetentionDays(n.intValue());
            }
        }
        if (body.containsKey("timeZoneId")) {
            Object tz = body.get("timeZoneId");
            if (tz instanceof String s) {
                appSettings.setTimeZoneId(s);
            }
        }
        if (body.containsKey("maxFileSizeMb")) {
            Object val = body.get("maxFileSizeMb");
            if (val instanceof Number n) {
                appSettings.setMaxFileSizeMb(n.intValue());
            }
        }
        if (body.containsKey("maxRequestSizeMb")) {
            Object val = body.get("maxRequestSizeMb");
            if (val instanceof Number n) {
                appSettings.setMaxRequestSizeMb(n.intValue());
            }
        }
        if (body.containsKey("duckdbQuackEnabled")) {
            Object val = body.get("duckdbQuackEnabled");
            if (val instanceof Boolean b) {
                appSettings.setDuckdbQuackEnabled(b);
                duckDbRepository.updateQuackServer();
            }
        }
        if (body.containsKey("queryParallelism")) {
            Object val = body.get("queryParallelism");
            if (val instanceof Number n) {
                appSettings.setQueryParallelism(n.intValue());
            }
        }
        return ResponseEntity.ok(appSettings.toMap());
    }
}
