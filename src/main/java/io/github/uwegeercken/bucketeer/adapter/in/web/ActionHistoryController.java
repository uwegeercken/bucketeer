package io.github.uwegeercken.bucketeer.adapter.in.web;

import tools.jackson.databind.ObjectMapper;
import io.github.uwegeercken.bucketeer.domain.model.ActionEntry;
import io.github.uwegeercken.bucketeer.infrastructure.config.TimeZoneProvider;
import io.github.uwegeercken.bucketeer.infrastructure.history.ActionHistory;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Controller
public class ActionHistoryController {

    private static final Logger log = LoggerFactory.getLogger(ActionHistoryController.class);
    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ActionHistory actionHistory;
    private final ObjectMapper mapper;
    private final TimeZoneProvider timeZoneProvider;

    public ActionHistoryController(ActionHistory actionHistory, ObjectMapper mapper,
                                   TimeZoneProvider timeZoneProvider) {
        this.actionHistory = actionHistory;
        this.mapper = mapper;
        this.timeZoneProvider = timeZoneProvider;
    }

    @GetMapping("/history")
    public String historyPage(Model model) {
        model.addAttribute("historyRows", actionHistory.list().stream()
                .map(e -> toDisplayRow(e, timeZoneProvider.getZone()))
                .toList());
        model.addAttribute("backUrl", "/");
        return "history";
    }

    @GetMapping(value = "/api/history", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<Map<String, Object>> historyJson() {
        return actionHistory.list().stream().map(ActionHistoryController::toMap).toList();
    }

    @PostMapping(value = "/api/history/clear", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> clearHistory() {
        actionHistory.clear();
        return Map.of("ok", true);
    }

    @GetMapping("/api/history/download")
    public void downloadHistory(HttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        response.setHeader("Content-Disposition", "attachment; filename=\"bucketeer-actions.json\"");
        try {
            mapper.writeValue(response.getWriter(),
                    actionHistory.list().stream().map(ActionHistoryController::toMap).toList());
        } catch (IOException e) {
            log.error("Failed to write action history download: {}", e.getMessage());
            throw e;
        }
    }

    private static Map<String, Object> toMap(ActionEntry e) {
        return Map.of(
                "timestamp", e.timestamp().toString(),
                "batchId",   e.batchId() != null ? e.batchId() : "",
                "server",    e.server(),
                "bucket",    e.bucket(),
                "sourceKey", e.sourceKey(),
                "targetKey", e.targetKey() != null ? e.targetKey() : "",
                "status",    e.status().name()
        );
    }

    private static Map<String, Object> toDisplayRow(ActionEntry e, ZoneId zone) {
        return Map.of(
                "time",      e.timestamp().atZone(zone).format(DISPLAY_FORMAT),
                "batchId",   e.batchId() != null ? e.batchId() : "",
                "status",    statusKey(e.status()),
                "server",    e.server(),
                "bucket",    e.bucket(),
                "sourceKey", e.sourceKey(),
                "targetKey", e.targetKey() != null ? e.targetKey() : ""
        );
    }

    private static String statusKey(ActionEntry.Status status) {
        if (status == ActionEntry.Status.NOT_AFFECTED) {
            return "notAffected";
        }
        return status.name().toLowerCase(Locale.ROOT);
    }
}
