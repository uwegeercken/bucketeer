package io.github.uwegeercken.bucketeer.adapter.in.web;

import io.github.uwegeercken.bucketeer.domain.model.S3Object;
import io.github.uwegeercken.bucketeer.domain.port.in.BucketeerUseCase;
import io.github.uwegeercken.bucketeer.domain.port.out.S3StoragePort;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Read-only, stateless REST API for terminal / scripted use.
 * Every endpoint takes the S3 server explicitly; nothing depends on the web session.
 */
@RestController
@RequestMapping("/api/v1")
public class ApiV1Controller {

    private static final Logger log = LoggerFactory.getLogger(ApiV1Controller.class);
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final BucketeerUseCase bucketeerUseCase;
    private final S3StoragePort s3StoragePort;
    private final BucketeerController bucketeerController;

    public ApiV1Controller(BucketeerUseCase bucketeerUseCase, S3StoragePort s3StoragePort,
                           BucketeerController bucketeerController) {
        this.bucketeerUseCase = bucketeerUseCase;
        this.s3StoragePort = s3StoragePort;
        this.bucketeerController = bucketeerController;
    }

    @GetMapping(value = "/servers", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<String> servers() {
        return bucketeerUseCase.serverNames();
    }

    @GetMapping(value = "/buckets", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> buckets(@RequestParam(required = false) String server) {
        if (server == null || server.isBlank()) {
            return error(400, "Server is required");
        }
        if (!serverExists(server)) {
            return error(400, "Unknown server: " + server);
        }
        try {
            return ResponseEntity.ok(bucketeerUseCase.listBuckets(server));
        } catch (Exception e) {
            log.error("Failed to list buckets for server {}: {}", server, e.getMessage());
            return error(500, e.getMessage());
        }
    }

    @GetMapping(value = "/resolve", produces = MediaType.TEXT_PLAIN_VALUE)
    @org.springframework.web.bind.annotation.ResponseBody
    public ResponseEntity<String> resolve(
            @RequestParam(required = false) String prefix,
            @RequestParam(required = false) String key,
            @RequestParam(required = false) String bucket) {
        if (prefix == null || prefix.isBlank()) {
            return ResponseEntity.badRequest().body("Error: Prefix is required");
        }
        try {
            return ResponseEntity.ok(bucketeerUseCase.resolveTemplate(prefix, key, bucket));
        } catch (Exception e) {
            log.error("Failed to resolve prefix '{}': {}", prefix, e.getMessage());
            return ResponseEntity.badRequest().body("Error: " + e.getMessage());
        }
    }

    @GetMapping(value = "/validate", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> validate(@RequestParam(required = false) String prefix) {
        if (!StringUtils.hasText(prefix)) {
            return ResponseEntity.ok(Map.of("valid", true, "error", ""));
        }
        List<String> unknown = bucketeerUseCase.validateTemplate(prefix);
        if (unknown.isEmpty()) {
            return ResponseEntity.ok(Map.of("valid", true, "error", ""));
        }
        return ResponseEntity.ok(Map.of("valid", false, "error", "Unknown function(s): " + String.join(", ", unknown)));
    }

    @GetMapping(value = "/list", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> list(
            @RequestParam(required = false) String server,
            @RequestParam(required = false) String bucket,
            @RequestParam(required = false) String prefix,
            @RequestParam(required = false) String key,
            @RequestParam(defaultValue = "0") long maxObjects) {
        if (server == null || server.isBlank()) {
            return error(400, "Server is required");
        }
        if (!serverExists(server)) {
            return error(400, "Unknown server: " + server);
        }
        if (!StringUtils.hasText(bucket)) {
            return error(400, "Bucket is required");
        }

        BucketeerController.SearchTarget target = bucketeerController.searchTarget(prefix, key, bucket);
        List<Map<String, Object>> objects = new ArrayList<>();
        try {
            bucketeerUseCase.fetchAllObjects(server, bucket, target.s3Prefix(), Math.max(0, maxObjects),
                    page -> page.objects().stream()
                            .filter(obj -> !obj.key().endsWith("/"))
                            .filter(obj -> target.keyFilter() == null || obj.key().equals(target.keyFilter()))
                            .forEach(obj -> objects.add(toObject(obj))));
            return ResponseEntity.ok(objects);
        } catch (Exception e) {
            log.error("Failed to list {}/{} prefix '{}': {}", server, bucket, target.s3Prefix(), e.getMessage());
            return error(500, e.getMessage());
        }
    }

    @GetMapping("/download")
    public void download(@RequestParam(required = false) String server,
                         @RequestParam(required = false) String bucket,
                         @RequestParam(required = false) String key,
                         HttpServletResponse response) throws IOException {
        if (server == null || server.isBlank()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Server is required");
            return;
        }
        if (!serverExists(server)) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unknown server: " + server);
            return;
        }
        if (!StringUtils.hasText(bucket) || !StringUtils.hasText(key)) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Bucket and key are required");
            return;
        }
        String filename = key.contains("/") ? key.substring(key.lastIndexOf('/') + 1) : key;
        response.setContentType("application/octet-stream");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        try (InputStream in = s3StoragePort.downloadObject(server, bucket, key)) {
            in.transferTo(response.getOutputStream());
        } catch (Exception e) {
            log.error("Failed to download {}/{}: {}", bucket, key, e.getMessage());
            if (!response.isCommitted()) {
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
            }
        }
    }

    @GetMapping("/download/prefix")
    public void downloadPrefix(@RequestParam(required = false) String server,
                               @RequestParam(required = false) String bucket,
                               @RequestParam(required = false) String prefix,
                               @RequestParam(required = false) String key,
                               @RequestParam(defaultValue = "0") long maxObjects,
                               HttpServletResponse response) throws IOException {
        if (server == null || server.isBlank()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Server is required");
            return;
        }
        if (!serverExists(server)) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unknown server: " + server);
            return;
        }
        if (!StringUtils.hasText(bucket)) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Bucket is required");
            return;
        }

        BucketeerController.SearchTarget target = bucketeerController.searchTarget(prefix, key, bucket);
        String filename = "bucketeer-download-" + LocalDateTime.now().format(TIMESTAMP) + ".zip";
        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        try (ZipOutputStream zos = new ZipOutputStream(response.getOutputStream())) {
            bucketeerUseCase.fetchAllObjects(server, bucket, target.s3Prefix(), Math.max(0, maxObjects),
                    page -> page.objects().stream()
                            .filter(obj -> !obj.key().endsWith("/"))
                            .filter(obj -> target.keyFilter() == null || obj.key().equals(target.keyFilter()))
                            .forEach(obj -> writeZipEntry(zos, server, bucket, obj)));
        } catch (Exception e) {
            log.error("Failed to create prefix download for {}/{}: {}", server, bucket, e.getMessage());
            if (!response.isCommitted()) {
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
            }
        }
    }

    private void writeZipEntry(ZipOutputStream zos, String server, String bucket, S3Object obj) {
        try {
            zos.putNextEntry(new ZipEntry(obj.key()));
            try (InputStream in = s3StoragePort.downloadObject(server, bucket, obj.key())) {
                in.transferTo(zos);
            }
            zos.closeEntry();
        } catch (Exception e) {
            log.error("Failed to zip object {}/{}: {}", bucket, obj.key(), e.getMessage());
        }
    }

    private boolean serverExists(String server) {
        return bucketeerUseCase.serverNames().contains(server);
    }

    private Map<String, Object> toObject(S3Object obj) {
        return Map.of(
                "key", obj.key(),
                "size_bytes", obj.sizeBytes(),
                "last_modified", obj.lastModified() != null ? obj.lastModified().toString() : "");
    }

    private ResponseEntity<Map<String, Object>> error(int status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}