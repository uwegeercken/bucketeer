package io.github.uwegeercken.bucketeer.adapter.in.web;

import io.github.uwegeercken.bucketeer.infrastructure.config.SnapshotRepository;
import io.github.uwegeercken.bucketeer.infrastructure.db.DuckDbRepository;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
                        "prefix",    m.prefix() != null ? m.prefix() : "",
                        "fileName",  m.dataPath(Path.of(".")).getFileName().toString()
                ))
                .toList();
    }

    @PostMapping("/api/snapshots/compare")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> compareSnapshots(
            @RequestBody List<String> ids) {

        if (ids == null || ids.size() != 2) {
            return ResponseEntity.badRequest().body(Map.of("error", "Exactly two snapshot IDs required"));
        }

        SnapshotMeta meta1 = snapshotRepo.findById(ids.get(0));
        SnapshotMeta meta2 = snapshotRepo.findById(ids.get(1));
        if (meta1 == null || meta2 == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Snapshot not found"));
        }

        String s1 = meta1.serverName() != null ? meta1.serverName() : "";
        String s2 = meta2.serverName() != null ? meta2.serverName() : "";
        String b1 = meta1.bucket() != null ? meta1.bucket() : "";
        String b2 = meta2.bucket() != null ? meta2.bucket() : "";
        String p1 = meta1.prefix() != null ? meta1.prefix().replaceAll("/+$", "") : "";
        String p2 = meta2.prefix() != null ? meta2.prefix().replaceAll("/+$", "") : "";
        if (!s1.equals(s2) || !b1.equals(b2) || !p1.equals(p2)) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Snapshots must have the same server, bucket and prefix"));
        }

        Path path1 = meta1.dataPath(snapshotRepo.getSnapshotsDir());
        Path path2 = meta2.dataPath(snapshotRepo.getSnapshotsDir());
        if (!path1.toFile().exists() || !path2.toFile().exists()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Snapshot data file not found"));
        }

        try {
            DuckDbRepository.DiffResult diff = duckDb.diffTwoSnapshots(path1.toString(), path2.toString());
            return ResponseEntity.ok(Map.<String, Object>of(
                    "snapshot1Name", meta1.name(),
                    "snapshot2Name", meta2.name(),
                    "added",         diff.added(),
                    "removed",       diff.removed(),
                    "changed",       diff.changed()));
        } catch (Exception e) {
            log.error("Failed to compare snapshots {} and {}: {}", ids.get(0), ids.get(1), e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Comparison failed: " + e.getMessage()));
        }
    }

    @GetMapping("/api/snapshots/diff/download")
    public ResponseEntity<Resource> downloadDiff(
            @RequestParam String id1,
            @RequestParam String id2) {

        SnapshotMeta meta1 = snapshotRepo.findById(id1);
        SnapshotMeta meta2 = snapshotRepo.findById(id2);
        if (meta1 == null || meta2 == null) {
            return ResponseEntity.badRequest().build();
        }

        Path path1 = meta1.dataPath(snapshotRepo.getSnapshotsDir());
        Path path2 = meta2.dataPath(snapshotRepo.getSnapshotsDir());
        if (!path1.toFile().exists() || !path2.toFile().exists()) {
            return ResponseEntity.badRequest().build();
        }

        try {
            java.io.File tmpFile = java.io.File.createTempFile("bucketeer-diff-", ".csv");
            tmpFile.deleteOnExit();
            duckDb.exportDiffToCsvTwoSnapshots(path1.toString(), path2.toString(), tmpFile.getAbsolutePath());

            String filename = "diff-" + meta1.name().replaceAll("[^a-zA-Z0-9._-]", "_")
                    + "-vs-" + meta2.name().replaceAll("[^a-zA-Z0-9._-]", "_") + ".csv";
            FileSystemResource resource = new FileSystemResource(tmpFile);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.parseMediaType("text/csv"))
                    .body(resource);
        } catch (Exception e) {
            log.error("Failed to export diff: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
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

    @PostMapping("/api/snapshots/{id}/reveal")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> revealSnapshot(@PathVariable String id) {
        SnapshotMeta meta = snapshotRepo.findById(id);
        if (meta == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Snapshot not found"));
        }

        Path parquetPath = meta.dataPath(snapshotRepo.getSnapshotsDir());
        if (!parquetPath.toFile().exists()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File not found"));
        }

        try {
            String os = System.getProperty("os.name").toLowerCase();
            String[] cmd;
            if (os.contains("mac")) {
                cmd = new String[]{"open", "-R", parquetPath.toAbsolutePath().toString()};
            } else if (os.contains("win")) {
                cmd = new String[]{"cmd", "/c", "explorer", "/select,", parquetPath.toAbsolutePath().toString()};
            } else {
                cmd = new String[]{"xdg-open", parquetPath.getParent().toAbsolutePath().toString()};
            }
            Runtime.getRuntime().exec(cmd);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (Exception e) {
            log.error("Failed to reveal file: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to open file manager: " + e.getMessage()));
        }
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
