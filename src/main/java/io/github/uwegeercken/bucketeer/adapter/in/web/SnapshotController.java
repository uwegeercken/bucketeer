package io.github.uwegeercken.bucketeer.adapter.in.web;

import io.github.uwegeercken.bucketeer.infrastructure.config.SnapshotRepository;
import io.github.uwegeercken.bucketeer.infrastructure.db.DuckDbRepository;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Controller
public class SnapshotController {

    private static final Logger log = LoggerFactory.getLogger(SnapshotController.class);

    private final DuckDbRepository duckDb;
    private final SnapshotRepository snapshotRepo;

    public SnapshotController(DuckDbRepository duckDb, SnapshotRepository snapshotRepo) {
        this.duckDb = duckDb;
        this.snapshotRepo = snapshotRepo;
    }

    @GetMapping("/snapshots")
    public String snapshotsPage(Model model) {
        model.addAttribute("backUrl", "/");
        return "snapshots";
    }

    @PostMapping("/api/snapshots")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> saveSnapshot(
            @RequestBody Map<String, String> body,
            HttpSession session) {

        QueryParams qp = (QueryParams) session.getAttribute("bucketeer_query_params");
        if (qp == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No query executed yet"));
        }

        String name = body.get("name");
        if (name == null || name.isBlank()) {
            name = autoName(qp);
        }
        long rowCount = duckDb.count();
        if (rowCount == 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "No data to snapshot"));
        }

        SnapshotMeta meta = SnapshotMeta.create(
                name, qp.serverName(), qp.bucket(), qp.prefix(),
                qp.key(), qp.dateFrom(), qp.dateTo(), qp.whereClause(),
                rowCount);

        Path parquetPath = meta.dataPath(snapshotRepo.getSnapshotsDir());
        try {
            duckDb.exportAllToParquet(parquetPath.toString());
            snapshotRepo.save(meta);
        } catch (IOException e) {
            log.error("Failed to save snapshot: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to save snapshot: " + e.getMessage()));
        }

        return ResponseEntity.ok(Map.of(
                "id", meta.id(),
                "name", meta.name(),
                "rowCount", meta.rowCount()));
    }

    @GetMapping(value = "/api/snapshots", produces = "application/json")
    @ResponseBody
    public List<Map<String, Object>> listSnapshots() {
        snapshotRepo.deleteExpired();
        return snapshotRepo.findAll().stream()
                .map(m -> Map.<String, Object>of(
                        "id",        m.id(),
                        "name",      m.name(),
                        "createdAt", m.createdAt().toString(),
                        "rowCount",  m.rowCount(),
                        "serverName", m.serverName() != null ? m.serverName() : "",
                        "bucket",    m.bucket() != null ? m.bucket() : "",
                        "prefix",    m.prefix() != null ? m.prefix() : ""
                ))
                .toList();
    }

    @PostMapping("/api/snapshots/{id}/compare")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> compareSnapshot(
            @PathVariable String id,
            HttpSession session) {

        QueryParams qp = (QueryParams) session.getAttribute("bucketeer_query_params");
        if (qp == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No query executed yet"));
        }

        SnapshotMeta meta = snapshotRepo.findById(id);
        if (meta == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Snapshot not found"));
        }

        Path parquetPath = meta.dataPath(snapshotRepo.getSnapshotsDir());
        if (!parquetPath.toFile().exists()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Snapshot data file not found"));
        }

        try {
            DuckDbRepository.DiffResult diff = duckDb.diffWithSnapshot(parquetPath.toString());
            return ResponseEntity.ok(Map.<String, Object>of(
                    "snapshotId",   meta.id(),
                    "snapshotName", meta.name(),
                    "added",        diff.added(),
                    "removed",      diff.removed(),
                    "changed",      diff.changed()));
        } catch (Exception e) {
            log.error("Failed to compare with snapshot {}: {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Comparison failed: " + e.getMessage()));
        }
    }

    @DeleteMapping("/api/snapshots/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteSnapshot(@PathVariable String id) {
        boolean deleted = snapshotRepo.delete(id);
        if (!deleted) {
            return ResponseEntity.badRequest().body(Map.of("error", "Snapshot not found"));
        }
        return ResponseEntity.ok(Map.of("deleted", true));
    }

    private static String autoName(QueryParams qp) {
        StringBuilder sb = new StringBuilder();
        if (qp.bucket() != null && !qp.bucket().isBlank()) {
            sb.append(qp.bucket());
        }
        if (qp.prefix() != null && !qp.prefix().isBlank()) {
            sb.append(" / ").append(qp.prefix());
        }
        if (qp.key() != null && !qp.key().isBlank()) {
            sb.append(qp.key());
        }
        if (sb.isEmpty()) sb.append("all");
        return sb.toString();
    }
}
