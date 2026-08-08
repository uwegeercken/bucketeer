package io.github.uwegeercken.bucketeer.adapter.in.web;

import io.github.uwegeercken.bucketeer.domain.model.ActionEntry;
import io.github.uwegeercken.bucketeer.domain.model.S3Object;
import io.github.uwegeercken.bucketeer.domain.port.in.BucketeerUseCase;
import io.github.uwegeercken.bucketeer.domain.port.out.S3StoragePort;
import io.github.uwegeercken.bucketeer.infrastructure.db.DuckDbRepository;
import io.github.uwegeercken.bucketeer.infrastructure.history.ActionHistory;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Controller
public class CartController {

    private static final Logger log = LoggerFactory.getLogger(CartController.class);
    public static final String SESSION_KEY = "bucketeer_cart";

    static final String MIXED_GROUP_MESSAGE =
            "Selection must contain objects of a single server and bucket.";

    private static final DateTimeFormatter BATCH_ID_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final S3StoragePort s3StoragePort;
    private final BucketeerUseCase bucketeerUseCase;
    private final ActionHistory actionHistory;
    private final DuckDbRepository duckDb;
    private final Map<String, BatchJob> batchJobs = new ConcurrentHashMap<>();
    private final ReentrantLock batchLock = new ReentrantLock();

    public CartController(S3StoragePort s3StoragePort, BucketeerUseCase bucketeerUseCase,
                          ActionHistory actionHistory, DuckDbRepository duckDb) {
        this.s3StoragePort    = s3StoragePort;
        this.bucketeerUseCase = bucketeerUseCase;
        this.actionHistory    = actionHistory;
        this.duckDb           = duckDb;
    }

    /** In-memory progress state of a background batch job (delete-selected / move-selected). */
    private static final class BatchJob {
        final AtomicInteger processed = new AtomicInteger();
        volatile int total;
        volatile boolean done;
        volatile Map<String, Object> result;
        volatile String error;

        BatchJob(int total) {
            this.total = total;
        }
    }

    /** Stable identity of a concrete object (server, bucket, key) used for de-duplication. */
    record EntryKey(String server, String bucket, String key) {}

    @SuppressWarnings("unchecked")
    private List<CartBatch> getCart(HttpSession session) {
        Object raw = session.getAttribute(SESSION_KEY);
        if (raw instanceof List<?>) {
            return (List<CartBatch>) raw;
        }
        List<CartBatch> cart = new ArrayList<>();
        session.setAttribute(SESSION_KEY, cart);
        return cart;
    }

    @GetMapping(value = "/api/cart/count", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> cartCount(HttpSession session) {
        return Map.of("count", getCart(session).size());
    }

    @GetMapping(value = "/api/cart", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<Map<String, Object>> cartItems(HttpSession session) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CartBatch b : getCart(session)) {
            Map<String, Object> m = new HashMap<>();
            m.put("type", "batch");
            m.put("id", b.id());
            m.put("seq", b.seq());
            m.put("serverName", b.serverName());
            m.put("bucket", b.bucket());
            m.put("prefix", b.prefix() == null ? "" : b.prefix());
            m.put("exactKey", b.exactKey() == null ? "" : b.exactKey());
            m.put("keyFilter", b.keyFilter() == null ? "" : b.keyFilter());
            m.put("minSizeKb", b.minSizeKb());
            m.put("maxSizeKb", b.maxSizeKb());
            m.put("dateFrom", b.dateFrom() == null ? "" : b.dateFrom());
            m.put("dateTo", b.dateTo() == null ? "" : b.dateTo());
            m.put("count", b.count());
            m.put("totalSizeBytes", b.totalSizeBytes());
            m.put("createdAt", b.createdAt() != null ? b.createdAt().toString() : "");
            m.put("keyCount", b.keys() == null ? 0 : b.keys().size());
            if (b.keys() != null && !b.keys().isEmpty()) {
                m.put("keyPreview", b.keys().subList(0, Math.min(3, b.keys().size())));
            }
            out.add(m);
        }
        return out;
    }

    @PostMapping(value = "/api/cart/add", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> addSelection(
            @RequestBody CartSelectionRequest req, HttpSession session) {
        List<CartBatch> cart = getCart(session);
        if (!isValidContext(req.serverName(), req.bucket())
                || req.items() == null || req.items().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "added", 0, "count", cart.size(),
                    "error", "Server, bucket and at least one object are required"));
        }
        List<String> keys = new ArrayList<>();
        long totalSizeBytes = 0;
        for (CartItemRequest it : req.items()) {
            if (it.key() == null || it.key().isBlank()) continue;
            if (!keys.contains(it.key())) {
                keys.add(it.key());
                totalSizeBytes += Math.max(0, it.sizeBytes());
            }
        }
        if (keys.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "added", 0, "count", cart.size(),
                    "error", "No keys provided"));
        }
        Set<String> normalized = new LinkedHashSet<>(keys);
        for (CartBatch b : cart) {
            if (b.keys() != null && new LinkedHashSet<>(b.keys()).equals(normalized)) {
                return ResponseEntity.ok(Map.of("count", cart.size(), "added", 0));
            }
        }
        CartBatch batch = new CartBatch(
                UUID.randomUUID().toString(), nextSeq(cart), req.serverName(), req.bucket(),
                commonPrefix(keys), null, null, null, null, null, null, 0,
                keys, keys.size(), totalSizeBytes, Instant.now());
        cart.add(batch);
        return ResponseEntity.ok(Map.of("count", cart.size(), "added", 1));
    }

    /** Computes the longest common prefix (truncated at the last '/') of the given keys. */
    static String commonPrefix(List<String> keys) {
        if (keys == null || keys.isEmpty()) return "";
        String p = keys.get(0);
        for (int i = 1; i < keys.size() && !p.isEmpty(); i++) {
            String k = keys.get(i);
            int j = 0;
            while (j < p.length() && j < k.length() && p.charAt(j) == k.charAt(j)) {
                j++;
            }
            p = p.substring(0, j);
        }
        int slash = p.lastIndexOf('/');
        return slash >= 0 ? p.substring(0, slash + 1) : "";
    }

    /**
     * Adds a query batch entry to the selection. Represents "all objects matching the current
     * query" without storing the individual keys - the batch is re-resolved against S3 when
     * an action (delete / move / download) is executed.
     */
    @PostMapping(value = "/api/cart/add-query", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> addQuery(
            @RequestParam String serverName,
            @RequestParam String bucket,
            @RequestParam(required = false) String prefix,
            @RequestParam(required = false) String key,
            @RequestParam(required = false) String keyFilter,
            @RequestParam(required = false) Double minSizeKb,
            @RequestParam(required = false) Double maxSizeKb,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(defaultValue = "0") long maxObjects,
            HttpSession session) {

        List<CartBatch> cart = getCart(session);
        if (!isValidContext(serverName, bucket)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("added", 0, "count", cart.size(),
                            "error", "Server and bucket are required"));
        }

        String resolved = bucketeerUseCase.resolveTemplate(prefix, key, bucket);
        String normalizedPrefix = (resolved != null && !resolved.isBlank() && !resolved.endsWith("/"))
                ? resolved + "/" : (resolved == null ? "" : resolved);

        String s3Prefix  = normalizedPrefix;
        String exactKey  = null;
        if (key != null && !key.isBlank()) {
            if (key.endsWith("*")) {
                s3Prefix = normalizedPrefix + key.substring(0, key.length() - 1);
            } else {
                s3Prefix = normalizedPrefix + key;
                exactKey = s3Prefix;
            }
        }

        CartBatch query = new CartBatch(
                UUID.randomUUID().toString(), nextSeq(cart), serverName, bucket,
                s3Prefix, exactKey, keyFilter, minSizeKb, maxSizeKb, dateFrom, dateTo,
                Math.max(0, maxObjects), List.of(),
                duckDb.queryCount(bucket, s3Prefix, keyFilter, minSizeKb, maxSizeKb, dateFrom, dateTo),
                duckDb.queryTotalSizeBytes(bucket, s3Prefix, keyFilter, minSizeKb, maxSizeKb, dateFrom, dateTo),
                Instant.now());
        cart.add(query);
        return ResponseEntity.ok(Map.of("count", cart.size(), "added", 1));
    }

    private static long nextSeq(List<CartBatch> cart) {
        return cart.stream().mapToLong(CartBatch::seq).max().orElse(0L) + 1;
    }

    private static boolean isValidContext(String serverName, String bucket) {
        return serverName != null && bucket != null
                && !serverName.isBlank() && !bucket.isBlank()
                && !"null".equals(serverName) && !"null".equals(bucket)
                && !"undefined".equals(serverName) && !"undefined".equals(bucket);
    }

    /** True if the given batches span more than one (server, bucket) group. */
    static boolean isMixedGroup(Collection<CartBatch> batches) {
        Set<String> groups = new HashSet<>();
        for (CartBatch b : batches) {
            if (b != null) {
                groups.add(b.serverName() + "\u0000" + b.bucket());
            }
        }
        return groups.size() > 1;
    }

    @PostMapping(value = "/api/cart/remove", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> removeFromCart(@RequestBody CartRemoveRequest req, HttpSession session) {
        List<CartBatch> cart = getCart(session);
        if (req.id() != null && !req.id().isBlank()) {
            cart.removeIf(b -> b.id().equals(req.id()));
        }
        return Map.of("count", cart.size());
    }

    /**
     * Removes an affected object from a key batch after a direct delete/move on the results page.
     * The batch entry is dropped once its last key is gone; live query batches stay untouched
     * (they are re-resolved against S3).
     */
    public static void removeItem(HttpSession session, String serverName, String bucket, String key) {
        Object raw = session.getAttribute(SESSION_KEY);
        if (raw instanceof List<?> rawList && !rawList.isEmpty() && rawList.get(0) instanceof CartBatch) {
            @SuppressWarnings("unchecked")
            List<CartBatch> list = (List<CartBatch>) rawList;
            for (int i = list.size() - 1; i >= 0; i--) {
                CartBatch b = list.get(i);
                if (b.isLiveQuery()) continue;
                if (!b.serverName().equals(serverName) || !b.bucket().equals(bucket)) continue;
                List<String> keys = new ArrayList<>(b.keys());
                keys.remove(key);
                if (keys.isEmpty()) {
                    list.remove(i);
                } else {
                    list.set(i, new CartBatch(b.id(), b.seq(), b.serverName(), b.bucket(),
                            b.prefix(), b.exactKey(), b.keyFilter(), b.minSizeKb(), b.maxSizeKb(),
                            b.dateFrom(), b.dateTo(), b.maxObjects(), keys, keys.size(),
                            b.totalSizeBytes(), b.createdAt()));
                }
                break;
            }
        }
    }

    @PostMapping(value = "/api/cart/delete-selected", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteSelected(
            @RequestBody List<CartActionRequest> items, HttpSession session) {
        List<CartBatch> involved = (items == null ? List.<CartActionRequest>of() : items).stream()
                .map(a -> a == null ? null : findBatch(session, a.id()))
                .filter(Objects::nonNull)
                .toList();
        if (isMixedGroup(involved)) {
            return ResponseEntity.badRequest().body(Map.of("error", MIXED_GROUP_MESSAGE));
        }
        String jobId = UUID.randomUUID().toString();
        BatchJob job = new BatchJob(0);
        batchJobs.put(jobId, job);
        cleanupBatchJobs();
        new Thread(() -> runDeleteSelected(job, items, session), "bucketeer-delete").start();
        return ResponseEntity.ok(Map.of("jobId", jobId, "total", 0, "resolving", true));
    }

    private void runDeleteSelected(BatchJob job, List<CartActionRequest> actions, HttpSession session) {
        String batchId = nextBatchId();
        Set<EntryKey> failedKeys = new HashSet<>();
        try {
            batchLock.lock();
            try {
                Set<EntryKey> keys = collectKeys(session, actions);
                job.total = keys.size();
                int deleted = 0;
                List<Map<String, Object>> results = new ArrayList<>();
                for (EntryKey k : keys) {
                    job.processed.incrementAndGet();
                    try {
                        bucketeerUseCase.deleteObject(k.server(), k.bucket(), k.key());
                        deleted++;
                        actionHistory.append(new ActionEntry(Instant.now(), ActionEntry.Action.DELETE, ActionEntry.Origin.SELECTION,
                                batchId, k.server(), k.bucket(), k.key(), null,
                                ActionEntry.Status.DELETED, null));
                        results.add(Map.of("key", k.key(), "status", "DELETED"));
                    } catch (Exception e) {
                        failedKeys.add(k);
                        log.error("Delete failed for {}/{}: {}", k.bucket(), k.key(), e.getMessage());
                        actionHistory.append(new ActionEntry(Instant.now(), ActionEntry.Action.DELETE, ActionEntry.Origin.SELECTION,
                                batchId, k.server(), k.bucket(), k.key(), null,
                                ActionEntry.Status.FAILED, e.getMessage()));
                        results.add(Map.of("key", k.key(), "status", "FAILED", "error", e.getMessage()));
                    }
                }
                job.result = Map.of(
                        "processed", keys.size(),
                        "deleted",   deleted,
                        "failed",    keys.size() - deleted,
                        "items",     results
                );
            } finally {
                batchLock.unlock();
            }
            removeProcessedBatches(session, actions, failedKeys);
        } catch (Exception e) {
            log.error("Delete-selected job failed: {}", e.getMessage(), e);
            job.error = e.getMessage();
        } finally {
            job.done = true;
        }
    }

    @PostMapping(value = "/api/cart/move-selected", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> moveSelected(@RequestBody CartMoveRequest req, HttpSession session) {
        List<CartBatch> involved = (req.queries() == null ? List.<CartActionRequest>of() : req.queries()).stream()
                .map(a -> a == null ? null : findBatch(session, a.id()))
                .filter(Objects::nonNull)
                .toList();
        boolean mixed = isMixedGroup(involved);
        if (!mixed && req.keys() != null && !req.keys().isEmpty()) {
            for (CartBatch b : involved) {
                if (!b.serverName().equals(req.serverName()) || !b.bucket().equals(req.bucket())) {
                    mixed = true;
                    break;
                }
            }
        }
        if (mixed) {
            return ResponseEntity.badRequest().body(Map.of("error", MIXED_GROUP_MESSAGE));
        }
        String jobId = UUID.randomUUID().toString();
        BatchJob job = new BatchJob(0);
        batchJobs.put(jobId, job);
        cleanupBatchJobs();
        new Thread(() -> runMoveSelected(job, req, session), "bucketeer-move").start();
        return ResponseEntity.ok(Map.of("jobId", jobId, "total", 0, "resolving", true));
    }

    private void runMoveSelected(BatchJob job, CartMoveRequest req, HttpSession session) {
        String batchId = nextBatchId();
        Set<EntryKey> failedKeys = new HashSet<>();
        try {
            batchLock.lock();
            try {
                Set<EntryKey> keys = collectMoveKeys(session, req);
                job.total = keys.size();
                int moved = 0;
                int skipped = 0;
                int failed = 0;
                List<Map<String, Object>> results = new ArrayList<>();
                for (EntryKey k : keys) {
                    job.processed.incrementAndGet();
                    String targetKey = targetKey(k.key(), req.toPrefix());
                    if (targetKey.equals(k.key())) {
                        skipped++;
                        actionHistory.append(new ActionEntry(Instant.now(), ActionEntry.Action.MOVE, ActionEntry.Origin.SELECTION,
                                batchId, k.server(), k.bucket(), k.key(), targetKey,
                                ActionEntry.Status.SKIPPED, "Target equals source"));
                        results.add(Map.of("key", k.key(), "targetKey", targetKey, "status", "SKIPPED"));
                        continue;
                    }
                    try {
                        boolean movedFlag = bucketeerUseCase.moveObject(k.server(), k.bucket(), k.key(), targetKey);
                        if (movedFlag) {
                            moved++;
                        } else {
                            skipped++;
                        }
                        actionHistory.append(new ActionEntry(Instant.now(), ActionEntry.Action.MOVE, ActionEntry.Origin.SELECTION,
                                batchId, k.server(), k.bucket(), k.key(), targetKey,
                                movedFlag ? ActionEntry.Status.MOVED : ActionEntry.Status.SKIPPED, null));
                        results.add(Map.of("key", k.key(), "targetKey", targetKey,
                                "status", movedFlag ? "MOVED" : "SKIPPED"));
                    } catch (Exception e) {
                        failed++;
                        failedKeys.add(k);
                        log.error("Move failed for {}/{}: {}", k.bucket(), k.key(), e.getMessage());
                        actionHistory.append(new ActionEntry(Instant.now(), ActionEntry.Action.MOVE, ActionEntry.Origin.SELECTION,
                                batchId, k.server(), k.bucket(), k.key(), targetKey,
                                ActionEntry.Status.FAILED, e.getMessage()));
                        results.add(Map.of("key", k.key(), "targetKey", targetKey, "status", "FAILED", "error", e.getMessage()));
                    }
                }
                job.result = Map.of(
                        "moved",   moved,
                        "skipped", skipped,
                        "failed",  failed,
                        "items",   results
                );
            } finally {
                batchLock.unlock();
            }
            removeProcessedBatches(session, req.queries(), failedKeys);
        } catch (Exception e) {
            log.error("Move-selected job failed: {}", e.getMessage(), e);
            job.error = e.getMessage();
        } finally {
            job.done = true;
        }
    }

    /**
     * Collects all concrete object keys of the given batch actions. Key batches use their stored
     * keys directly; live query batches are re-resolved live against S3 (current state, with the
     * stored filters applied).
     */
    private Set<EntryKey> collectKeys(HttpSession session, List<CartActionRequest> actions) {
        Set<EntryKey> keys = new LinkedHashSet<>();
        for (CartActionRequest action : actions) {
            CartBatch batch = findBatch(session, action.id());
            if (batch != null) {
                collectBatchKeys(batch, keys);
            }
        }
        return keys;
    }

    private Set<EntryKey> collectMoveKeys(HttpSession session, CartMoveRequest req) {
        Set<EntryKey> keys = new LinkedHashSet<>();
        if (req.keys() != null) {
            for (String key : req.keys()) {
                if (key != null && !key.endsWith("/")) {
                    keys.add(new EntryKey(req.serverName(), req.bucket(), key));
                }
            }
        }
        if (req.queries() != null) {
            for (CartActionRequest action : req.queries()) {
                CartBatch batch = findBatch(session, action.id());
                if (batch != null) {
                    collectBatchKeys(batch, keys);
                }
            }
        }
        return keys;
    }

    private void collectBatchKeys(CartBatch batch, Set<EntryKey> sink) {
        if (!batch.isLiveQuery()) {
            for (String key : batch.keys()) {
                if (key.endsWith("/")) continue;
                sink.add(new EntryKey(batch.serverName(), batch.bucket(), key));
            }
        } else {
            resolveQuery(batch, obj -> sink.add(new EntryKey(batch.serverName(), batch.bucket(), obj.key())));
        }
    }

    private CartBatch findBatch(HttpSession session, String id) {
        if (id == null) return null;
        for (CartBatch b : getCart(session)) {
            if (b.id().equals(id)) {
                return b;
            }
        }
        return null;
    }

    /**
     * Re-resolves a live query batch against the S3 server: lists the stored prefix and applies the
     * stored listing key filter and the result-list filters (key regex, size range, date range).
     */
    private void resolveQuery(CartBatch batch, Consumer<S3Object> sink) {
        bucketeerUseCase.fetchAllObjects(batch.serverName(), batch.bucket(), batch.prefix(),
                batch.maxObjects(), page -> {
                    for (S3Object obj : page.objects()) {
                        if (obj.key().endsWith("/")) continue;
                        if (batch.exactKey() != null && !batch.exactKey().equals(obj.key())) continue;
                        if (!matchesFilters(obj.key(), obj.sizeBytes(), obj.lastModified(),
                                batch.keyFilter(), batch.minSizeKb(), batch.maxSizeKb(),
                                batch.dateFrom(), batch.dateTo())) continue;
                        sink.accept(obj);
                    }
                });
    }

    /**
     * Removes the batch entries that were executed successfully. A batch is only removed when none
     * of its objects failed - failed keys stay in the cart so the operation can be retried.
     */
    private void removeProcessedBatches(HttpSession session, List<CartActionRequest> actions,
                                        Set<EntryKey> failedKeys) {
        List<CartBatch> cart = getCart(session);
        Set<String> processedIds = new HashSet<>();
        if (actions != null) {
            for (CartActionRequest action : actions) {
                if (action.id() != null) {
                    processedIds.add(action.id());
                }
            }
        }
        cart.removeIf(b -> processedIds.contains(b.id()) && batchFullySucceeded(b, failedKeys));
    }

    /** True if none of the batch's objects failed; live query batches are kept when anything failed. */
    static boolean batchFullySucceeded(CartBatch batch, Set<EntryKey> failedKeys) {
        if (failedKeys == null || failedKeys.isEmpty()) {
            return true;
        }
        if (batch.isLiveQuery()) {
            return false;
        }
        for (String key : batch.keys()) {
            if (failedKeys.contains(new EntryKey(batch.serverName(), batch.bucket(), key))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Matches a listed object against the result-list filters. Mirrors the DuckDB filter semantics
     * used by {@code /api/query/results}: keyFilter is a regex (search), sizes are in KB, dates are
     * inclusive day boundaries.
     */
    static boolean matchesFilters(String key, long sizeBytes, Instant lastModified,
                                  String keyFilter, Double minSizeKb, Double maxSizeKb,
                                  String dateFrom, String dateTo) {
        if (keyFilter != null && !keyFilter.isBlank()) {
            try {
                if (!Pattern.compile(keyFilter).matcher(key).find()) {
                    return false;
                }
            } catch (PatternSyntaxException e) {
                // invalid regex behaves like no filter (same as the DuckDB path)
            }
        }
        if (minSizeKb != null && sizeBytes < minSizeKb * 1024) return false;
        if (maxSizeKb != null && sizeBytes > maxSizeKb * 1024) return false;
        if (lastModified != null) {
            if (dateFrom != null && !dateFrom.isBlank()) {
                Instant from = parseDayStart(dateFrom);
                if (from != null && lastModified.isBefore(from)) return false;
            }
            if (dateTo != null && !dateTo.isBlank()) {
                Instant to = parseDayEnd(dateTo);
                if (to != null && lastModified.isAfter(to)) return false;
            }
        }
        return true;
    }

    /** Parses a day start boundary in UTC; invalid values behave like no filter (mirrors invalid regex). */
    private static Instant parseDayStart(String date) {
        try {
            return LocalDate.parse(date).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Parses a day end boundary in UTC; invalid values behave like no filter. */
    private static Instant parseDayEnd(String date) {
        try {
            return LocalDate.parse(date).atTime(23, 59, 59).toInstant(ZoneOffset.UTC);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @GetMapping(value = "/api/cart/batch/{jobId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> batchProgress(@PathVariable String jobId) {
        BatchJob job = batchJobs.get(jobId);
        if (job == null) {
            return Map.of("jobId", jobId, "found", false);
        }
        Map<String, Object> resp = new HashMap<>();
        resp.put("jobId", jobId);
        resp.put("found", true);
        resp.put("total", job.total);
        resp.put("processed", job.processed.get());
        resp.put("done", job.done);
        if (job.error != null) {
            resp.put("error", job.error);
        }
        if (job.result != null) {
            resp.put("result", job.result);
        }
        return resp;
    }

    private void cleanupBatchJobs() {
        if (batchJobs.size() > 20) {
            batchJobs.entrySet().removeIf(e -> e.getValue().done);
        }
    }

    /**
     * Builds the target key by replacing the object's own folder (the path up to the last '/')
     * with the target prefix. Examples (toPrefix "archive/"):
     *   "a/b/c.txt"            -&gt; "archive/c.txt"
     *   "a/b/sub/c.txt"        -&gt; "archive/c.txt"
     *   "top.txt"              -&gt; "archive/top.txt"
     * An empty target prefix moves the object to the bucket root.
     */
    static String targetKey(String key, String toPrefix) {
        String rel = key;
        int idx = key.lastIndexOf('/');
        if (idx >= 0) {
            rel = key.substring(idx + 1);
        }
        String target = toPrefix == null ? "" : toPrefix;
        if (!target.isEmpty() && !target.endsWith("/") && !rel.isEmpty()) {
            target = target + "/";
        }
        return target + rel;
    }

    private String nextBatchId() {
        return LocalDateTime.now().format(BATCH_ID_FORMAT);
    }

    @PostMapping(value = "/api/cart/clear", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> clearCart(HttpSession session) {
        session.setAttribute(SESSION_KEY, new ArrayList<>());
        return Map.of("count", 0);
    }

    @GetMapping("/cart")
    public String cartPage(HttpSession session, Model model) {
        List<CartBatch> cart = getCart(session);
        model.addAttribute("cartItems", cart);
        long totalBytes = cart.stream().mapToLong(CartBatch::totalSizeBytes).sum();
        model.addAttribute("totalSize", formatSize(totalBytes));
        model.addAttribute("backUrl", "/");
        return "cart";
    }

    private static String formatSize(long bytes) {
        if (bytes >= 1024L * 1024) {
            return String.format(Locale.ROOT, "%.2f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format(Locale.ROOT, "%.2f KB", bytes / 1024.0);
    }

    @GetMapping("/cart/download-all")
    public void downloadAll(HttpSession session,
                            @RequestParam(required = false) List<String> ids,
                            HttpServletResponse response) throws IOException {
        List<CartBatch> cart = getCart(session);
        List<CartBatch> batches = cart;
        if (ids != null) {
            List<String> cleanIds = ids.stream()
                    .filter(i -> i != null && !i.isBlank())
                    .toList();
            if (cleanIds.isEmpty()) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "No batches selected");
                return;
            }
            batches = cart.stream()
                    .filter(b -> cleanIds.contains(b.id()))
                    .toList();
            if (batches.isEmpty()) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "No batches selected");
                return;
            }
        }
        if (batches.isEmpty()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Cart is empty");
            return;
        }
        if (isMixedGroup(batches)) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, MIXED_GROUP_MESSAGE);
            return;
        }

        String timestamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String filename = "bucketeer-cart-" + timestamp + ".zip";

        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");

        try (ZipOutputStream zos = new ZipOutputStream(response.getOutputStream())) {
            Map<String, Integer> nameCount = new HashMap<>();
            for (CartBatch batch : batches) {
                if (!batch.isLiveQuery()) {
                    for (String key : batch.keys()) {
                        writeZipEntry(zos, nameCount, batch.serverName(), batch.bucket(), key);
                    }
                } else {
                    resolveQuery(batch, obj -> writeZipEntry(zos, nameCount, batch.serverName(), batch.bucket(), obj.key()));
                }
            }
        } catch (Exception e) {
            log.error("Failed to create zip download: {}", e.getMessage());
        }
    }

    private void writeZipEntry(ZipOutputStream zos, Map<String, Integer> nameCount,
                               String serverName, String bucket, String key) {
        String entryName = key;
        int slash = key.lastIndexOf('/');
        if (slash >= 0) {
            entryName = key.substring(slash + 1);
        }
        nameCount.merge(entryName, 1, Integer::sum);
        if (nameCount.get(entryName) > 1) {
            int dot = entryName.lastIndexOf('.');
            if (dot > 0) {
                entryName = entryName.substring(0, dot) + "-" + nameCount.get(entryName) + entryName.substring(dot);
            } else {
                entryName = entryName + "-" + nameCount.get(entryName);
            }
        }
        try {
            zos.putNextEntry(new ZipEntry(entryName));
            try (InputStream in = s3StoragePort.downloadObject(serverName, bucket, key)) {
                in.transferTo(zos);
            }
            zos.closeEntry();
        } catch (Exception e) {
            log.error("Failed to zip object {}/{}: {}", bucket, key, e.getMessage());
        }
    }

    public record CartItemRequest(
            String key,
            long sizeBytes,
            java.time.Instant lastModified
    ) {}

    /** A checkbox selection added to the cart: one batch entry holding the exact key list. */
    public record CartSelectionRequest(
            String serverName,
            String bucket,
            List<CartItemRequest> items
    ) {}

    /** Payload for removing a batch entry from the cart (by id). */
    public record CartRemoveRequest(
            String id
    ) {}

    /** A cart action item: a batch entry referenced by id (key batch or live query batch). */
    public record CartActionRequest(
            String id
    ) {}

    public record CartMoveRequest(
            String serverName,
            String bucket,
            String toPrefix,
            List<String> keys,
            List<CartActionRequest> queries
    ) {}
}
