package io.github.uwegeercken.bucketeer.adapter.in.web;

import io.github.uwegeercken.bucketeer.domain.model.HeadObjectResult;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

@Controller
public class KeyCheckController {

    private static final Logger log = LoggerFactory.getLogger(KeyCheckController.class);

    private final S3StoragePort s3StoragePort;
    private final SessionContext sessionContext;
    private final ThreadPoolTaskExecutor executor;

    public KeyCheckController(S3StoragePort s3StoragePort,
                              SessionContext sessionContext,
                              ThreadPoolTaskExecutor executor) {
        this.s3StoragePort = s3StoragePort;
        this.sessionContext = sessionContext;
        this.executor = executor;
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

        List<String> keys = parseCsvKeys(file, sep, hasHeader);
        if (keys.isEmpty()) {
            return Map.of("error", "No keys found in file");
        }

        int total = keys.size();
        CopyOnWriteArrayList<Map<String, Object>> results = new CopyOnWriteArrayList<>();
        int[] processed = {0};

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (String key : keys) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                HeadObjectResult result = s3StoragePort.headObject(serverName, bucket, key);
                results.add(Map.of(
                        "key", key,
                        "exists", result.exists(),
                        "sizeBytes", result.sizeBytes(),
                        "lastModified", result.lastModified() != null ? result.lastModified().toString() : "",
                        "etag", result.eTag() != null ? result.eTag() : ""
                ));
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
            sb.append("key").append(sep).append("exists").append(sep)
              .append("size_bytes").append(sep).append("last_modified").append(sep)
              .append("etag").append("\n");
        }

        for (Map<String, Object> row : results) {
            sb.append(row.get("key")).append(sep)
              .append(row.get("exists")).append(sep)
              .append(row.get("sizeBytes")).append(sep)
              .append(row.get("lastModified")).append(sep)
              .append(row.get("etag")).append("\n");
        }

        response.getOutputStream().write(sb.toString().getBytes(StandardCharsets.UTF_8));
        response.getOutputStream().flush();
    }

    private char parseDelimiter(String delimiter) {
        return switch (delimiter) {
            case "tab" -> '\t';
            case "pipe" -> '|';
            case "semicolon" -> ';';
            default -> ',';
        };
    }

    private List<String> parseCsvKeys(MultipartFile file, char sep, boolean hasHeader) throws IOException {
        List<String> keys = new ArrayList<>();
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
                if (!trimmed.isEmpty()) {
                    String[] parts = trimmed.split(String.valueOf(sep), -1);
                    if (parts.length > 0 && !parts[0].trim().isEmpty()) {
                        keys.add(parts[0].trim());
                    }
                }
            }
        }
        return keys;
    }
}
