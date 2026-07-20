package io.github.uwegeercken.bucketeer.adapter.in.web;

import io.github.uwegeercken.bucketeer.domain.model.ObjectListing;
import io.github.uwegeercken.bucketeer.domain.model.S3Object;
import io.github.uwegeercken.bucketeer.domain.port.in.BucketeerUseCase;
import io.github.uwegeercken.bucketeer.domain.port.out.S3StoragePort;
import io.github.uwegeercken.bucketeer.infrastructure.db.DuckDbRepository;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
public class BucketeerController {

    private static final Logger log = LoggerFactory.getLogger(BucketeerController.class);

    private final BucketeerUseCase bucketeerUseCase;
    private final S3StoragePort s3StoragePort;
    private final SessionContext sessionContext;
    private final DuckDbRepository duckDb;
    private final ThreadPoolTaskExecutor executor;

    public BucketeerController(BucketeerUseCase bucketeerUseCase,
                               S3StoragePort s3StoragePort,
                               SessionContext sessionContext,
                               DuckDbRepository duckDb,
                               ThreadPoolTaskExecutor executor) {
        this.bucketeerUseCase = bucketeerUseCase;
        this.s3StoragePort    = s3StoragePort;
        this.sessionContext   = sessionContext;
        this.duckDb           = duckDb;
        this.executor         = executor;
    }

    @GetMapping("/")
    public String index(
            @RequestParam(required = false) String bucket,
            @RequestParam(required = false) String prefix,
            @RequestParam(required = false) String key,
            @RequestParam(required = false) Boolean search,
            HttpSession session,
            Model model) {

        String currentServer = sessionContext.getSelectedServer();

        model.addAttribute("availableFunctions", bucketeerUseCase.availableFunctions());
        model.addAttribute("bucket", bucket);
        model.addAttribute("prefix", prefix);
        model.addAttribute("key", key);

        String tooltipText = "<strong>Available functions:</strong><br/>" +
                bucketeerUseCase.availableFunctions().stream()
                        .map(f -> "<code>{" + f + "(ref, ...)}</code>")
                        .collect(Collectors.joining("<br/>"));
        model.addAttribute("tooltipText", tooltipText);

        if (Boolean.TRUE.equals(search) && currentServer != null && StringUtils.hasText(bucket)) {
            String resolvedPrefix = bucketeerUseCase.resolveTemplate(prefix, key);

            String normalizedPrefix = StringUtils.hasText(resolvedPrefix) && !resolvedPrefix.endsWith("/")
                    ? resolvedPrefix + "/" : resolvedPrefix;

            String s3Prefix  = normalizedPrefix;
            String keyFilter = null;

            if (StringUtils.hasText(key)) {
                if (key.endsWith("*")) {
                    s3Prefix = normalizedPrefix + key.substring(0, key.length() - 1);
                } else {
                    s3Prefix = normalizedPrefix + key;
                    keyFilter = key;
                }
            }

            final String finalS3Prefix  = s3Prefix;
            final String finalKeyFilter = keyFilter;
            final String finalServer    = currentServer;
            final String finalBucket    = bucket;

            // create QueryContext as plain object and store in session
            QueryContext qc = new QueryContext();
            session.setAttribute(QueryContext.SESSION_KEY, qc);

            duckDb.clear();
            qc.start();

            executor.execute(() -> {
                try {
                    bucketeerUseCase.fetchAllObjects(finalServer, finalBucket, finalS3Prefix, page -> {
                        List<S3Object> filtered = page.objects().stream()
                                .filter(obj -> !obj.key().endsWith("/"))
                                .filter(obj -> finalKeyFilter == null ||
                                        obj.key().equals(finalKeyFilter))
                                .toList();
                        duckDb.insertBatch(filtered);
                        qc.incrementFound(filtered.size());
                    });
                    qc.done();
                } catch (Exception e) {
                    qc.error(e.getMessage());
                }
            });

            model.addAttribute("queryStarted", true);
        } else {
            QueryContext qc = (QueryContext) session.getAttribute(QueryContext.SESSION_KEY);
            if (qc != null && qc.getStatus() == QueryContext.Status.DONE && duckDb.count() > 0) {
                model.addAttribute("queryStarted", true);
            }
        }

        return "index";
    }

    @PostMapping("/session/server")
    public String selectServer(@RequestParam String serverName) {
        sessionContext.setSelectedServer(serverName);
        return "redirect:/";
    }

    @GetMapping(value = "/api/buckets", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<String> listBuckets() {
        String server = sessionContext.getSelectedServer();
        if (server == null) return List.of();
        try {
            return bucketeerUseCase.listBuckets(server);
        } catch (Exception e) {
            log.warn("Failed to list buckets for server {}: {}", server, e.getMessage());
            return List.of();
        }
    }

    @GetMapping(value = "/api/query/status", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> queryStatus(HttpSession session) {
        QueryContext qc = (QueryContext) session.getAttribute(QueryContext.SESSION_KEY);
        if (qc == null) {
            return Map.of("status", "IDLE", "objectsFound", 0L, "error", "");
        }
        return Map.of(
                "status",       qc.getStatus().name(),
                "objectsFound", qc.getObjectsFound(),
                "error",        qc.getErrorMessage() != null ? qc.getErrorMessage() : ""
        );
    }

    @GetMapping(value = "/api/query/results", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> queryResults(
            @RequestParam(required = false) String keyFilter,
            @RequestParam(required = false) Long minSizeKb,
            @RequestParam(required = false) Long maxSizeKb,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int pageSize) {

        List<S3Object> results = duckDb.query(keyFilter, minSizeKb, maxSizeKb, dateFrom, dateTo, page, pageSize);
        long total = duckDb.queryCount(keyFilter, minSizeKb, maxSizeKb, dateFrom, dateTo);

        List<Map<String, Object>> rows = results.stream()
                .map(obj -> Map.<String, Object>of(
                        "key",          obj.key(),
                        "bucket",       obj.bucket(),
                        "sizeKb",       String.format("%.2f", obj.sizeBytes() / 1024.0),
                        "lastModified", obj.lastModified() != null ? obj.lastModified().toString() : "",
                        "etag",         obj.etag() != null ? obj.etag() : ""
                ))
                .toList();

        return Map.of(
                "rows",     rows,
                "total",    total,
                "page",     page,
                "pageSize", pageSize,
                "hasMore",  (long)(page + 1) * pageSize < total
        );
    }

    @GetMapping("/api/query/export")
    public void exportParquet(
            @RequestParam(required = false) String keyFilter,
            @RequestParam(required = false) Long minSizeKb,
            @RequestParam(required = false) Long maxSizeKb,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(required = false) String bucket,
            HttpServletResponse response) throws IOException {

        String timestamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String bucketPart = (bucket != null && !bucket.isBlank())
                ? bucket.replaceAll("[^a-zA-Z0-9_-]", "_") + "-" : "";
        String filename = "bucketeer-" + bucketPart + timestamp + ".parquet";

        java.nio.file.Path exportDir = java.nio.file.Path.of(
                System.getProperty("user.home"), ".bucketeer", "exports");
        try {
            java.nio.file.Files.createDirectories(exportDir);
        } catch (IOException e) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Could not create export directory: " + e.getMessage());
            return;
        }

        java.nio.file.Path exportPath = exportDir.resolve(filename);

        try {
            long count = duckDb.exportToParquet(exportPath.toString(),
                    keyFilter, minSizeKb, maxSizeKb, dateFrom, dateTo);

            response.setContentType("application/octet-stream");
            response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
            response.setHeader("X-Export-Count", String.valueOf(count));

            try (InputStream in = java.nio.file.Files.newInputStream(exportPath)) {
                in.transferTo(response.getOutputStream());
            }
        } finally {
            // delete temp file after streaming
            try { java.nio.file.Files.deleteIfExists(exportPath); }
            catch (IOException e) { log.warn("Could not delete export file: {}", exportPath); }
        }
    }

    @GetMapping(value = "/api/resolve-prefix", produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseBody
    public String resolvePrefix(
            @RequestParam(required = false) String prefix,
            @RequestParam(required = false) String key) {
        if (!StringUtils.hasText(prefix)) return "";
        try {
            return bucketeerUseCase.resolveTemplate(prefix, key);
        } catch (Exception e) {
            return "";
        }
    }

    @GetMapping(value = "/api/validate-prefix", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> validatePrefix(@RequestParam(required = false) String prefix) {
        if (!StringUtils.hasText(prefix)) return Map.of("valid", true, "error", "");
        List<String> unknown = bucketeerUseCase.validateTemplate(prefix);
        if (unknown.isEmpty()) return Map.of("valid", true, "error", "");
        return Map.of("valid", false, "error", "Unknown function(s): " + String.join(", ", unknown));
    }

    @GetMapping("/download")
    public void download(
            @RequestParam String bucket,
            @RequestParam String key,
            HttpServletResponse response) throws IOException {

        String currentServer = sessionContext.getSelectedServer();
        if (currentServer == null) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "No server selected");
            return;
        }
        String filename = key.contains("/") ? key.substring(key.lastIndexOf('/') + 1) : key;
        response.setContentType("application/octet-stream");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        try (InputStream in = s3StoragePort.downloadObject(currentServer, bucket, key)) {
            in.transferTo(response.getOutputStream());
        }
    }
}