package io.github.uwegeercken.bucketeer.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uwegeercken.bucketeer.domain.model.ActionEntry;
import io.github.uwegeercken.bucketeer.infrastructure.history.ActionHistory;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Controller
public class ActionHistoryController {

    private static final Logger log = LoggerFactory.getLogger(ActionHistoryController.class);

    private final ActionHistory actionHistory;
    private final ObjectMapper mapper;

    public ActionHistoryController(ActionHistory actionHistory, ObjectMapper mapper) {
        this.actionHistory = actionHistory;
        this.mapper = mapper;
    }

    @GetMapping("/history")
    public String historyPage(Model model) {
        model.addAttribute("historyRows", actionHistory.list().stream()
                .map(ActionHistoryController::toDisplayRow)
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

    private static Map<String, Object> toDisplayRow(ActionEntry e) {
        return Map.of(
                "time",      e.timestamp().atZone(java.time.ZoneId.systemDefault())
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
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
        return status.name().toLowerCase(java.util.Locale.ROOT);
    }
}
