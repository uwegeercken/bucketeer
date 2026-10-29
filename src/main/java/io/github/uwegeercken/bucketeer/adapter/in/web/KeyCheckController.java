package io.github.uwegeercken.bucketeer.adapter.in.web;

import io.github.uwegeercken.bucketeer.domain.model.Etags;
import io.github.uwegeercken.bucketeer.domain.model.HeadObjectResult;
import io.github.uwegeercken.bucketeer.domain.port.in.BucketeerUseCase;
import io.github.uwegeercken.bucketeer.domain.port.out.S3StoragePort;
import io.github.uwegeercken.bucketeer.infrastructure.config.AppSettings;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Controller
public class KeyCheckController {

    private static final Logger log = LoggerFactory.getLogger(KeyCheckController.class);

    private final S3StoragePort s3StoragePort;
    private final SessionContext sessionContext;
    private final ThreadPoolTaskExecutor executor;
    private final BucketeerUseCase bucketeerUseCase;
    private final AppSettings appSettings;

    public KeyCheckController(S3StoragePort s3StoragePort,
                              SessionContext sessionContext,
                              @Qualifier("taskExecutor") ThreadPoolTaskExecutor executor,
                              BucketeerUseCase bucketeerUseCase,
                              AppSettings appSettings) {
        this.s3StoragePort = s3StoragePort;
        this.sessionContext = sessionContext;
        this.executor = executor;
        this.bucketeerUseCase = bucketeerUseCase;
        this.appSettings = appSettings;
    }

    @GetMapping("/keycheck")
    public String keyCheckPage(Model model) {
        model.addAttribute("backUrl", "/");
        return "keycheck";
    }

    @PostMapping("/api/keycheck/check")
    @ResponseBody
    public Map<String, Object> startCheck(
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

        KeyCheckJob job = new KeyCheckJob(lines.size());
        session.setAttribute(KeyCheckJob.SESSION_KEY, job);

        // A single orchestrating task on the shared executor (like the search); the HEAD fan-out
        // itself runs on its own pool so a long check cannot starve concurrent searches. The
        // request returns immediately and the browser polls /api/keycheck/status for progress.
        executor.execute(() -> {
            try {
                runCheck(job, serverName, bucket, lines);
            } catch (RuntimeException e) {
                log.error("Key check failed: {}", e.getMessage(), e);
                job.fail(e.getMessage());
            }
        });

        return Map.of("started", true, "total", lines.size());
    }

    @GetMapping(value = "/api/keycheck/status", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> checkStatus(HttpSession session) {
        KeyCheckJob job = (KeyCheckJob) session.getAttribute(KeyCheckJob.SESSION_KEY);
        if (job == null) {
            return Map.of("status", "IDLE");
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("status", job.getStatus().name());
        payload.put("total", job.getTotal());
        payload.put("processed", job.getProcessed());
        payload.put("existCount", job.getExistCount());
        payload.put("missingCount", job.getMissingCount());
        payload.put("errorCount", job.getErrorCount());
        // True while a cancel request is pending: the running check still has to observe the flag.
        payload.put("cancelling", job.isCancelled() && job.getStatus() == KeyCheckJob.Status.RUNNING);
        payload.put("error", job.getErrorMessage());
        return payload;
    }

    @PostMapping(value = "/api/keycheck/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> cancelCheck(HttpSession session) {
        KeyCheckJob job = (KeyCheckJob) session.getAttribute(KeyCheckJob.SESSION_KEY);
        if (job != null && job.getStatus() == KeyCheckJob.Status.RUNNING) {
            job.cancel();
        }
        return checkStatus(session);
    }

    @GetMapping(value = "/api/keycheck/results", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> checkResults(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "pageSize", defaultValue = "100") int pageSize,
            @RequestParam(value = "filter", defaultValue = "all") String filter,
            HttpSession session) {

        KeyCheckJob job = (KeyCheckJob) session.getAttribute(KeyCheckJob.SESSION_KEY);
        List<Map<String, Object>> all = job != null ? job.getRows() : List.of();

        List<Map<String, Object>> filtered = "all".equals(filter)
                ? all
                : all.stream().filter(r -> matchesFilter(r, filter)).toList();

        int size = Math.max(1, Math.min(pageSize, 1000));
        int current = Math.max(0, page);
        int from = Math.min(current * size, filtered.size());
        int to = Math.min(from + size, filtered.size());

        Map<String, Object> payload = new HashMap<>();
        payload.put("rows", filtered.subList(from, to));
        payload.put("total", filtered.size());
        payload.put("allTotal", all.size());
        payload.put("page", current);
        payload.put("pageSize", size);
        payload.put("hasMore", to < filtered.size());
        return payload;
    }

    private static boolean matchesFilter(Map<String, Object> row, String filter) {
        return switch (filter) {
            case "exists"  -> row.get("error") == null && Boolean.TRUE.equals(row.get("exists"));
            case "missing" -> row.get("error") == null && !Boolean.TRUE.equals(row.get("exists"));
            case "error"   -> row.get("error") != null;
            default        -> true;
        };
    }

    /**
     * Runs the S3 existence check for all parsed lines and publishes the rows into the job.
     * Package-private so tests can drive it synchronously, without the executor.
     */
    void runCheck(KeyCheckJob job, String serverName, String bucket, List<KeyLine> lines) {
        int total = lines.size();
        @SuppressWarnings("unchecked")
        Map<String, Object>[] rows = new Map[total];

        // Lines that failed to parse need no S3 call; resolve them up front. Valid lines are kept
        // by index so the workers can fill distinct slots without sharing a mutable list.
        int[] checkable = new int[total];
        int n = 0;
        for (int i = 0; i < total; i++) {
            KeyLine line = lines.get(i);
            if (line.error() != null) {
                record(job, rows, i, errorRow(line.rawLine(), line.prefix(), line.key(), line.error()));
            } else {
                checkable[n++] = i;
            }
        }

        final int count = n;
        if (count > 0) {
            int workers = Math.min(count, Math.max(1, appSettings.getQueryParallelism()));
            // A dedicated pool per check guarantees exactly `workers` threads: a shared pool with
            // core/max/queue sizing only grows past the core size once its queue is full, which
            // would silently under-provision the check (see BucketeerService for the same reason).
            AtomicInteger threadSeq = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(workers, runnable -> {
                Thread thread = new Thread(runnable, "keycheck-" + threadSeq.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
            try {
                List<CompletableFuture<Void>> futures = new ArrayList<>(workers);
                for (int w = 0; w < workers; w++) {
                    final int offset = w;
                    futures.add(CompletableFuture.runAsync(() -> {
                        for (int k = offset; k < count; k += workers) {
                            if (job.isCancelled()) {
                                return;
                            }
                            int idx = checkable[k];
                            record(job, rows, idx, checkOne(serverName, bucket, lines.get(idx)));
                        }
                    }, pool));
                }
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            } finally {
                pool.shutdownNow();
            }
        }

        job.finish(compact(rows));
    }

    /** Stores one finished row and advances the counters the status endpoint reports. */
    private void record(KeyCheckJob job, Map<String, Object>[] rows, int index, Map<String, Object> value) {
        rows[index] = value;
        if (value.get("error") != null) {
            job.addError();
        } else if (Boolean.TRUE.equals(value.get("exists"))) {
            job.addExists();
        } else {
            job.addMissing();
        }
        job.incrementProcessed();
    }

    /** Sorts the finished rows by key and drops the gaps a cancelled check leaves behind. */
    private List<Map<String, Object>> compact(Map<String, Object>[] rows) {
        List<Map<String, Object>> result = new ArrayList<>(rows.length);
        for (Map<String, Object> row : rows) {
            if (row != null) {
                result.add(row);
            }
        }
        result.sort(Comparator.comparing(row -> String.valueOf(row.get("key"))));
        return result;
    }

    /**
     * Runs the S3 existence check for one parsed line and returns its result row. Never throws;
     * a template-resolution or S3 failure becomes an error row, exactly as before.
     */
    private Map<String, Object> checkOne(String serverName, String bucket, KeyLine line) {
        final String fullKey;
        try {
            fullKey = assembleFullKey(line.prefix(), line.key(), bucket);
        } catch (RuntimeException e) {
            return errorRow(line.rawLine(), line.prefix(), line.key(), e.getMessage());
        }
        try {
            HeadObjectResult result = s3StoragePort.headObject(serverName, bucket, fullKey);
            Map<String, Object> row = new HashMap<>();
            row.put("key", fullKey);
            row.put("prefix", line.prefix());
            row.put("rawKey", line.key());
            row.put("exists", result.exists());
            row.put("sizeBytes", result.sizeBytes());
            row.put("lastModified", result.lastModified() != null ? result.lastModified().toString() : "");
            row.put("etag", result.eTag() != null ? Etags.stripQuotes(result.eTag()) : "");
            return row;
        } catch (Exception e) {
            log.error("Key check failed for {}/{}: {}", bucket, fullKey, e.getMessage());
            return errorRow(fullKey, line.prefix(), line.key(), e.getMessage());
        }
    }

    @GetMapping("/api/keycheck/export")
    public void exportCsv(
            @RequestParam(value = "delimiter", defaultValue = ",") String delimiter,
            @RequestParam(value = "hasHeader", defaultValue = "false") boolean hasHeader,
            HttpServletResponse response,
            HttpSession session) throws IOException {

        KeyCheckJob job = (KeyCheckJob) session.getAttribute(KeyCheckJob.SESSION_KEY);
        List<Map<String, Object>> results = job != null ? job.getRows() : null;
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
            Object etag = row.get("etag");
            sb.append(row.get("prefix")).append(sep)
              .append(row.get("rawKey")).append(sep)
              .append(row.get("exists")).append(sep)
              .append(row.get("sizeBytes")).append(sep)
              .append(row.get("lastModified")).append(sep)
              .append(Etags.stripQuotes(etag != null ? etag.toString() : "")).append("\n");
        }

        response.getOutputStream().write(sb.toString().getBytes(StandardCharsets.UTF_8));
        response.getOutputStream().flush();
    }

    record KeyLine(String prefix, String key, String rawLine, String error) {
    }

    /**
     * Parses the uploaded file into two-column lines (prefix, key).
     * A single-column line or an empty key column is reported as an error row;
     * blank lines are skipped. The delimiter is only recognized outside of
     * braces, so commas inside a prefix template (e.g. {everyNth(p4, 0, 2)})
     * do not split the line.
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
                List<String> parts = splitColumnsOutsideBraces(trimmed, sep);
                if (parts.size() < 2) {
                    lines.add(new KeyLine("", "", trimmed, "Expected two columns: <prefix>" + sep + "<key>"));
                } else {
                    String prefix = parts.get(0).trim();
                    String key = String.join(String.valueOf(sep), parts.subList(1, parts.size())).trim();
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
     * Splits a line at the given separator, ignoring separators inside { } braces
     * (the same rule the template parser applies to '/', see TemplateParser).
     */
    private List<String> splitColumnsOutsideBraces(String line, char sep) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth = Math.max(0, depth - 1);
            } else if (c == sep && depth == 0) {
                parts.add(line.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(line.substring(start));
        return parts;
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
