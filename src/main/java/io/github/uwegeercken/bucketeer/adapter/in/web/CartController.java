package io.github.uwegeercken.bucketeer.adapter.in.web;

import io.github.uwegeercken.bucketeer.domain.model.ActionEntry;
import io.github.uwegeercken.bucketeer.domain.port.in.BucketeerUseCase;
import io.github.uwegeercken.bucketeer.domain.port.out.S3StoragePort;
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
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Controller
public class CartController {

    private static final Logger log = LoggerFactory.getLogger(CartController.class);
    public static final String SESSION_KEY = "bucketeer_cart";

    private static final DateTimeFormatter BATCH_ID_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final S3StoragePort s3StoragePort;
    private final BucketeerUseCase bucketeerUseCase;
    private final ActionHistory actionHistory;

    public CartController(S3StoragePort s3StoragePort, BucketeerUseCase bucketeerUseCase, ActionHistory actionHistory) {
        this.s3StoragePort    = s3StoragePort;
        this.bucketeerUseCase = bucketeerUseCase;
        this.actionHistory    = actionHistory;
    }

    @SuppressWarnings("unchecked")
    private List<CartItem> getCart(HttpSession session) {
        Object raw = session.getAttribute(SESSION_KEY);
        if (raw instanceof List<?>) {
            return (List<CartItem>) raw;
        }
        List<CartItem> cart = new ArrayList<>();
        session.setAttribute(SESSION_KEY, cart);
        return cart;
    }

    /** Removes all cart items matching the given server/bucket/key. Returns true if anything was removed. */
    public static boolean removeItem(HttpSession session, String serverName, String bucket, String key) {
        Object raw = session.getAttribute(SESSION_KEY);
        if (!(raw instanceof List<?>)) {
            return false;
        }
        List<CartItem> cart = (List<CartItem>) raw;
        boolean removed = cart.removeIf(ci -> ci.serverName().equals(serverName)
                && ci.bucket().equals(bucket)
                && ci.key().equals(key));
        if (removed) {
            session.setAttribute(SESSION_KEY, cart);
        }
        return removed;
    }

    @GetMapping(value = "/api/cart/count", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> cartCount(HttpSession session) {
        return Map.of("count", getCart(session).size());
    }

    @GetMapping(value = "/api/cart", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<Map<String, Object>> cartItems(HttpSession session) {
        return getCart(session).stream().map(item -> Map.<String, Object>of(
                "serverName",   item.serverName(),
                "bucket",       item.bucket(),
                "key",          item.key(),
                "filename",     item.filename(),
                "sizeBytes",    item.sizeBytes(),
                "sizeKb",       String.format("%.2f", item.sizeBytes() / 1024.0),
                "lastModified", item.lastModified() != null ? item.lastModified().toString() : ""
        )).toList();
    }

    @PostMapping(value = "/api/cart/add", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> addToCart(@RequestBody List<CartItemRequest> items, HttpSession session) {
        List<CartItem> cart = getCart(session);
        Set<String> existing = new HashSet<>();
        for (CartItem item : cart) {
            existing.add(item.serverName() + "|" + item.bucket() + "|" + item.key());
        }
        int added = 0;
        for (CartItemRequest req : items) {
            String id = req.serverName() + "|" + req.bucket() + "|" + req.key();
            if (!existing.contains(id)) {
                cart.add(new CartItem(req.serverName(), req.bucket(), req.key(),
                        req.sizeBytes(), req.lastModified()));
                existing.add(id);
                added++;
            }
        }
        return Map.of("count", cart.size(), "added", added);
    }

    @PostMapping(value = "/api/cart/remove", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> removeFromCart(@RequestBody CartItemRequest item, HttpSession session) {
        removeItem(session, item.serverName(), item.bucket(), item.key());
        return Map.of("count", getCart(session).size());
    }

    @PostMapping(value = "/api/cart/delete-selected", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> deleteSelected(@RequestBody List<CartItemRequest> items, HttpSession session) {
        String batchId = nextBatchId();
        int processed = 0;
        int deleted = 0;
        List<Map<String, Object>> results = new ArrayList<>();
        for (CartItemRequest item : items) {
            processed++;
            try {
                bucketeerUseCase.deleteObject(item.serverName(), item.bucket(), item.key());
                removeItem(session, item.serverName(), item.bucket(), item.key());
                deleted++;
                actionHistory.append(new ActionEntry(Instant.now(), ActionEntry.Action.DELETE, ActionEntry.Origin.SELECTION,
                        batchId, item.serverName(), item.bucket(), item.key(), null,
                        ActionEntry.Status.DELETED, null));
                results.add(Map.of("key", item.key(), "status", "DELETED"));
            } catch (Exception e) {
                log.error("Delete failed for {}/{}: {}", item.bucket(), item.key(), e.getMessage());
                actionHistory.append(new ActionEntry(Instant.now(), ActionEntry.Action.DELETE, ActionEntry.Origin.SELECTION,
                        batchId, item.serverName(), item.bucket(), item.key(), null,
                        ActionEntry.Status.FAILED, e.getMessage()));
                results.add(Map.of("key", item.key(), "status", "FAILED", "error", e.getMessage()));
            }
        }
        return Map.of(
                "processed", processed,
                "deleted",   deleted,
                "failed",    processed - deleted,
                "items",     results
        );
    }

    @PostMapping(value = "/api/cart/move-selected", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> moveSelected(@RequestBody CartMoveRequest req, HttpSession session) {
        String batchId = nextBatchId();
        int moved = 0;
        int skipped = 0;
        int failed = 0;
        List<Map<String, Object>> results = new ArrayList<>();

        List<CartItem> snapshot = new ArrayList<>(getCart(session));
        for (CartItem item : snapshot) {
            if (!item.serverName().equals(req.serverName()) || !item.bucket().equals(req.bucket())
                    || !req.keys().contains(item.key())) {
                continue;
            }
            String targetKey = targetKey(item.key(), req.toPrefix());
            if (targetKey.equals(item.key())) {
                skipped++;
                actionHistory.append(new ActionEntry(Instant.now(), ActionEntry.Action.MOVE, ActionEntry.Origin.SELECTION,
                        batchId, item.serverName(), item.bucket(), item.key(), targetKey,
                        ActionEntry.Status.SKIPPED, "Target equals source"));
                results.add(Map.of("key", item.key(), "targetKey", targetKey, "status", "SKIPPED"));
                continue;
            }
            try {
                boolean movedFlag = bucketeerUseCase.moveObject(item.serverName(), item.bucket(), item.key(), targetKey);
                removeItem(session, item.serverName(), item.bucket(), item.key());
                if (movedFlag) {
                    moved++;
                } else {
                    skipped++;
                }
                actionHistory.append(new ActionEntry(Instant.now(), ActionEntry.Action.MOVE, ActionEntry.Origin.SELECTION,
                        batchId, item.serverName(), item.bucket(), item.key(), targetKey,
                        movedFlag ? ActionEntry.Status.MOVED : ActionEntry.Status.SKIPPED, null));
                results.add(Map.of("key", item.key(), "targetKey", targetKey,
                        "status", movedFlag ? "MOVED" : "SKIPPED"));
            } catch (Exception e) {
                failed++;
                log.error("Move failed for {}/{}: {}", item.bucket(), item.key(), e.getMessage());
                actionHistory.append(new ActionEntry(Instant.now(), ActionEntry.Action.MOVE, ActionEntry.Origin.SELECTION,
                        batchId, item.serverName(), item.bucket(), item.key(), targetKey,
                        ActionEntry.Status.FAILED, e.getMessage()));
                results.add(Map.of("key", item.key(), "targetKey", targetKey, "status", "FAILED", "error", e.getMessage()));
            }
        }
        return Map.of(
                "moved",   moved,
                "skipped", skipped,
                "failed",  failed,
                "items",   results
        );
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
        List<CartItem> cart = getCart(session);
        model.addAttribute("cartItems", cart);
        long totalBytes = cart.stream().mapToLong(CartItem::sizeBytes).sum();
        model.addAttribute("totalSizeKb", String.format("%.2f", totalBytes / 1024.0));
        model.addAttribute("backUrl", "/");
        return "cart";
    }

    @GetMapping("/cart/download-all")
    public void downloadAll(HttpSession session, HttpServletResponse response) throws IOException {
        List<CartItem> cart = getCart(session);
        if (cart.isEmpty()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Cart is empty");
            return;
        }

        String timestamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String filename = "bucketeer-cart-" + timestamp + ".zip";

        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");

        try (ZipOutputStream zos = new ZipOutputStream(response.getOutputStream())) {
            Map<String, Integer> nameCount = new HashMap<>();
            for (CartItem item : cart) {
                String serverName = item.serverName();
                String bucket = item.bucket();
                String key = item.key();
                String entryName = item.filename();

                nameCount.merge(entryName, 1, Integer::sum);
                if (nameCount.get(entryName) > 1) {
                    int dot = entryName.lastIndexOf('.');
                    if (dot > 0) {
                        entryName = entryName.substring(0, dot) + "-" + nameCount.get(entryName) + entryName.substring(dot);
                    } else {
                        entryName = entryName + "-" + nameCount.get(entryName);
                    }
                }

                zos.putNextEntry(new ZipEntry(entryName));
                try (InputStream in = s3StoragePort.downloadObject(serverName, bucket, key)) {
                    in.transferTo(zos);
                }
                zos.closeEntry();
            }
        } catch (Exception e) {
            log.error("Failed to create zip download: {}", e.getMessage());
        }
    }

    public record CartItemRequest(
            String serverName,
            String bucket,
            String key,
            long sizeBytes,
            java.time.Instant lastModified
    ) {}

    public record CartMoveRequest(
            String serverName,
            String bucket,
            String toPrefix,
            List<String> keys
    ) {}
}
