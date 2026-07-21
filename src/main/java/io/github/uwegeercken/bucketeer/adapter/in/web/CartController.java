package io.github.uwegeercken.bucketeer.adapter.in.web;

import io.github.uwegeercken.bucketeer.domain.port.out.S3StoragePort;
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
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Controller
public class CartController {

    private static final Logger log = LoggerFactory.getLogger(CartController.class);
    public static final String SESSION_KEY = "bucketeer_cart";

    private final S3StoragePort s3StoragePort;

    public CartController(S3StoragePort s3StoragePort) {
        this.s3StoragePort = s3StoragePort;
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
        List<CartItem> cart = getCart(session);
        cart.removeIf(ci -> ci.serverName().equals(item.serverName())
                && ci.bucket().equals(item.bucket())
                && ci.key().equals(item.key()));
        return Map.of("count", cart.size());
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
            log.error("Failed to create zip download: {}", e.getMessage(), e);
        }
    }

    public record CartItemRequest(
            String serverName,
            String bucket,
            String key,
            long sizeBytes,
            java.time.Instant lastModified
    ) {}
}
