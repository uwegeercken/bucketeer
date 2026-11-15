package io.github.uwegeercken.bucketeer.adapter.in.web;

import tools.jackson.databind.ObjectMapper;
import io.github.uwegeercken.bucketeer.domain.model.ActionEntry;
import io.github.uwegeercken.bucketeer.domain.port.in.BucketeerUseCase;
import io.github.uwegeercken.bucketeer.infrastructure.config.TimeZoneProvider;
import io.github.uwegeercken.bucketeer.infrastructure.db.DuckDbRepository;
import io.github.uwegeercken.bucketeer.infrastructure.history.ActionHistory;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.Instant;
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
    private final BucketeerUseCase bucketeerUseCase;
    private final DuckDbRepository duckDb;

    public ActionHistoryController(ActionHistory actionHistory, ObjectMapper mapper,
                                   TimeZoneProvider timeZoneProvider,
                                   BucketeerUseCase bucketeerUseCase,
                                   DuckDbRepository duckDb) {
        this.actionHistory = actionHistory;
        this.mapper = mapper;
        this.timeZoneProvider = timeZoneProvider;
        this.bucketeerUseCase = bucketeerUseCase;
        this.duckDb = duckDb;
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

    /**
     * Macht eine Verschiebung aus dem Aktionsverlauf rueckgaengig: das Objekt wird vom Ziel
     * exakt auf seinen urspruenglichen Schluessel zurueckverschoben - auf dem Server des
     * Eintrags, nicht dem der Session. Existiert am Ursprung bereits ein Objekt, wird nichts
     * ueberschrieben (Status SKIPPED). Das Zurueckverschieben wird selbst protokolliert.
     */
    @PostMapping(value = "/api/history/undo", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> undoMove(@RequestBody UndoRequest req, HttpSession session) {
        if (req == null || isBlank(req.server()) || isBlank(req.bucket())
                || isBlank(req.sourceKey()) || isBlank(req.targetKey())) {
            return Map.of("ok", false, "error", "Server, bucket, source and target key are required");
        }
        if (req.sourceKey().equals(req.targetKey())) {
            return Map.of("ok", false, "error", "Source and target key are identical");
        }
        if (!bucketeerUseCase.serverNames().contains(req.server())) {
            return Map.of("ok", false, "error", "Unknown server: " + req.server());
        }
        // Rueckweg: aktueller Ort (targetKey) -> urspruenglicher Ort (sourceKey)
        String from = req.targetKey();
        String to = req.sourceKey();
        boolean moved;
        try {
            moved = bucketeerUseCase.moveObject(req.server(), req.bucket(), from, to);
        } catch (Exception e) {
            log.error("Undo move failed for {}/{}: {}", req.bucket(), from, e.getMessage());
            actionHistory.append(new ActionEntry(Instant.now(), ActionEntry.Action.MOVE, ActionEntry.Origin.RESULTS,
                    null, req.server(), req.bucket(), from, to, ActionEntry.Status.FAILED, e.getMessage()));
            return Map.of("ok", false, "error", e.getMessage() != null ? e.getMessage() : "Move failed");
        }
        if (!moved) {
            actionHistory.append(new ActionEntry(Instant.now(), ActionEntry.Action.MOVE, ActionEntry.Origin.RESULTS,
                    null, req.server(), req.bucket(), from, to, ActionEntry.Status.SKIPPED,
                    "Target already exists"));
            return Map.of("ok", true, "skipped", true);
        }
        try {
            duckDb.deleteByKey(req.bucket(), from);
            CartController.removeItem(session, req.server(), req.bucket(), from);
        } catch (Exception e) {
            log.error("Failed to update results/cart after undo {}/{}: {}", req.bucket(), from, e.getMessage());
        }
        actionHistory.append(new ActionEntry(Instant.now(), ActionEntry.Action.MOVE, ActionEntry.Origin.RESULTS,
                null, req.server(), req.bucket(), from, to, ActionEntry.Status.MOVED, null));
        return Map.of("ok", true, "skipped", false);
    }

    public record UndoRequest(String server, String bucket, String sourceKey, String targetKey) {}

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
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
