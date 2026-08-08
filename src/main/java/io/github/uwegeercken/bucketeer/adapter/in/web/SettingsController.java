package io.github.uwegeercken.bucketeer.adapter.in.web;

import io.github.uwegeercken.bucketeer.infrastructure.config.AppSettings;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.ZoneId;
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

    public SettingsController(AppSettings appSettings) {
        this.appSettings = appSettings;
    }

    @GetMapping("/settings")
    public String settings(Model model) {
        model.addAttribute("backUrl", "/");
        model.addAttribute("snapshotRetentionDays", appSettings.getSnapshotRetentionDays());
        model.addAttribute("timeZoneId", appSettings.getTimeZoneId());
        model.addAttribute("systemTimeZoneId", ZoneId.systemDefault().getId());
        model.addAttribute("timeZoneOptions", COMMON_ZONES);
        return "settings";
    }

    @GetMapping(value = "/api/settings", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> getSettings() {
        return appSettings.toMap();
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
        return ResponseEntity.ok(appSettings.toMap());
    }
}
