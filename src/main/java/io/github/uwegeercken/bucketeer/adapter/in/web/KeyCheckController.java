package io.github.uwegeercken.bucketeer.adapter.in.web;

import io.github.uwegeercken.bucketeer.domain.model.HeadObjectResult;
import io.github.uwegeercken.bucketeer.domain.port.in.BucketeerUseCase;
import io.github.uwegeercken.bucketeer.domain.port.out.S3StoragePort;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

@Controller
public class KeyCheckController {

    private static final Logger log = LoggerFactory.getLogger(KeyCheckController.class);

    private final S3StoragePort s3StoragePort;
    private final SessionContext sessionContext;
    private final ThreadPoolTaskExecutor executor;
    private final BucketeerUseCase bucketeerUseCase;

    public KeyCheckController(S3StoragePort s3StoragePort,
                              SessionContext sessionContext,
                              ThreadPoolTaskExecutor executor,
                              BucketeerUseCase bucketeerUseCase) {
        this.s3StoragePort = s3StoragePort;
        this.sessionContext = sessionContext;
        this.executor = executor;
        this.bucketeerUseCase = bucketeerUseCase;
    }

    @GetMapping("/keycheck")
    public String keyCheckPage(Model model) {
        model.addAttribute("backUrl", "/");
        return "keycheck";
    }

    @PostMapping("/api/keycheck/check")
    @ResponseBody
    public Map<String, Object> checkKeys(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "delimiter", defaultValue = ",") String delimiter,
            @RequestParam(value = "hasHeader", defaultValue = "false") boolean hasHeader,
            @RequestParam("bucket") String bucket,
            HttpSession session) throws IOException {

        String serverName = sessionContext.getSelectedServer();
        if (serverName == null) {
            return Map.of("error", "No server selected");
        }

        char sep = parseDelimiter(delimiter);

        List<KeyLine> lines = parseKeyLines(file, sep, hasHeader);
        if (lines.isEmpty()) {
            return Map.of("error", "No keys found in file");
        }

        int total = lines.size();
        CopyOnWriteArrayList<Map<String, Object>> results = new CopyOnWriteArrayList<>();
        int[] processed = {0};

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (KeyLine line : lines) {
            if (line.error() != null) {
                results.add(errorRow(line.rawLine(), line.prefix(), line.key(), line.error()));
                synchronized (processed) {
                    processed[0]++;
                }
                continue;
            }
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                final String fullKey;
                try {
                    fullKey = assembleFullKey(line.prefix(), line.key(), bucket);
                } catch (RuntimeException e) {
                    log.error("Prefix resolution failed for {}: {}", line.rawLine(), e.getMessage());
                    results.add(errorRow(line.rawLine(), line.prefix(), line.key(), e.getMessage()));
                    synchronized (processed) {
                        processed[0]++;
                    }
                    return;
                }
                Map<String, Object> row;
                try {
                    HeadObjectResult result = s3StoragePort.headObject(serverName, bucket, fullKey);
                    row = new HashMap<>();
                    row.put("key", fullKey);
                    row.put("prefix", line.prefix());
                    row.put("rawKey", line.key());
                    row.put("exists", result.exists());
                    row.put("sizeBytes", result.sizeBytes());
                    row.put("lastModified", result.lastModified() != null ? result.lastModified().toString() : "");
                    row.put("etag", result.eTag() != null ? result.eTag() : "");
                } catch (Exception e) {
                    log.error("Key check failed for {}/{}: {}", bucket, fullKey, e.getMessage());
                    row = errorRow(fullKey, line.prefix(), line.key(), e.getMessage());
                }
                results.add(row);
                synchronized (processed) {
                    processed[0]++;
                }
            }, executor);
            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        List<Map<String, Object>> sorted = results.stream()
                .sorted((a, b) -> ((String) a.get("key")).compareTo((String) b.get("key")))
                .toList();

        session.setAttribute("keycheck_results", sorted);
        session.setAttribute("keycheck_delimiter", delimiter);
        session.setAttribute("keycheck_hasHeader", hasHeader);

        long existCount = sorted.stream().filter(r -> (Boolean) r.get("exists")).count();

        return Map.of(
                "results", sorted,
                "total", total,
                "processed", processed[0],
                "existCount", existCount,
                "missingCount", total - existCount,
                "delimiter", delimiter,
                "hasHeader", hasHeader
        );
    }

    @GetMapping("/api/keycheck/export")
    public void exportCsv(
            @RequestParam(value = "delimiter", defaultValue = ",") String delimiter,
            @RequestParam(value = "hasHeader", defaultValue = "false") boolean hasHeader,
            HttpServletResponse response,
            HttpSession session) throws IOException {

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results =
                (List<Map<String, Object>>) session.getAttribute("keycheck_results");
        if (results == null || results.isEmpty()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "No results to export");
            return;
        }

        char sep = parseDelimiter(delimiter);
        String filename = "keycheck-result.csv";

        response.setContentType("text/csv");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");

        StringBuilder sb = new StringBuilder();
        if (hasHeader) {
            sb.append("prefix").append(sep).append("key").append(sep)
              .append("exists").append(sep).append("size_bytes").append(sep)
              .append("last_modified").append(sep).append("etag").append("\n");
        }

        for (Map<String, Object> row : results) {
            sb.append(row.get("prefix")).append(sep)
              .append(row.get("rawKey")).append(sep)
              .append(row.get("exists")).append(sep)
              .append(row.get("sizeBytes")).append(sep)
              .append(row.get("lastModified")).append(sep)
              .append(row.get("etag")).append("\n");
        }

        response.getOutputStream().write(sb.toString().getBytes(StandardCharsets.UTF_8));
        response.getOutputStream().flush();
    }

    record KeyLine(String prefix, String key, String rawLine, String error) {
    }

    /**
     * Parses the uploaded file into two-column lines (prefix, key).
     * A single-column line or an empty key column is reported as an error row;
     * blank lines are skipped.
     */
    List<KeyLine> parseKeyLines(MultipartFile file, char sep, boolean hasHeader) throws IOException {
        List<KeyLine> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first && hasHeader) {
                    first = false;
                    continue;
                }
                first = false;
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                String[] parts = trimmed.split(Pattern.quote(String.valueOf(sep)), -1);
                if (parts.length < 2) {
                    lines.add(new KeyLine("", "", trimmed, "Expected two columns: <prefix>" + sep + "<key>"));
                } else {
                    String prefix = parts[0].trim();
                    String key = String.join(String.valueOf(sep), Arrays.copyOfRange(parts, 1, parts.length)).trim();
                    if (key.isEmpty()) {
                        if (prefix.isEmpty()) {
                            continue;
                        }
                        lines.add(new KeyLine(prefix, "", trimmed, "Missing key in second column"));
                    } else {
                        lines.add(new KeyLine(prefix, key, trimmed, null));
                    }
                }
            }
        }
        return lines;
    }

    /**
     * Builds the full object key from the uploaded prefix and key.
     * The prefix may be empty (check the key as-is), a literal path, or a prefix template
     * resolved by the template engine. A missing trailing slash on the resolved prefix is added.
     */
    String assembleFullKey(String prefix, String key, String bucket) {
        if (prefix == null || prefix.isEmpty()) {
            return key;
        }
        String resolved = bucketeerUseCase.resolveTemplate(prefix, key, bucket);
        String normalized = resolved.isEmpty()
                ? ""
                : resolved.endsWith("/") ? resolved : resolved + "/";
        return normalized + key;
    }

    private Map<String, Object> errorRow(String key, String prefix, String rawKey, String error) {
        Map<String, Object> row = new HashMap<>();
        row.put("key", key);
        row.put("prefix", prefix);
        row.put("rawKey", rawKey);
        row.put("exists", false);
        row.put("sizeBytes", null);
        row.put("lastModified", "");
        row.put("etag", "");
        row.put("error", error);
        return row;
    }

    private char parseDelimiter(String delimiter) {
        return switch (delimiter) {
            case "tab" -> '\t';
            case "pipe" -> '|';
            case "semicolon" -> ';';
            default -> ',';
        };
    }
}
