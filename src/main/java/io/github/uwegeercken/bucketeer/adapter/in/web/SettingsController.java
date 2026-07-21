package io.github.uwegeercken.bucketeer.adapter.in.web;

import io.github.uwegeercken.bucketeer.infrastructure.config.AppSettings;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Controller
public class SettingsController {

    private final AppSettings appSettings;

    public SettingsController(AppSettings appSettings) {
        this.appSettings = appSettings;
    }

    @GetMapping("/settings")
    public String settings(Model model) {
        model.addAttribute("backUrl", "/");
        model.addAttribute("snapshotRetentionDays", appSettings.getSnapshotRetentionDays());
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
        return ResponseEntity.ok(appSettings.toMap());
    }
}
